/**
 * qlapp 后端：情侣相册 / 任务 / 小游戏 / 日记 / 留言 / 五子棋
 * 跑在 Cloudflare Workers 上，结构化数据存 D1，照片原图存 KV。
 *
 * 只有两个人用，所以没有登录、没有配对码、也没有用户表：
 * 打开 App 就是主页，两个人共用同一份云端数据。
 *
 * 鉴权靠一个共享密钥（只有两台设备知道）：
 *   普通请求走 `X-App-Key` 头；图片 URL 走 `?key=` 查询参数
 *   （Coil 加载远程图时带不了自定义头，只能塞进 query 里）。
 *
 * 「谁做的」有两套标识，分工必须分清：
 *   - `X-Identity` 身份名（小江 / 甜甜）—— **归属看它**。日记、留言是不是我写的，只认这个。
 *     身份在 App 里只选一次（选完落盘），所以它稳定、且两个人天然不重名。
 *   - `X-Device-Id` 装置标识 —— 只记「这条写在哪儿」，以及算留言墙未读。
 *     它由 ANDROID_ID 派生、重装不变；可正因为重装不变，一台手机上换过身份之后，
 *     两个人的内容就会落到同一个 ID 上。所以**不能**拿它判归属（踩过，见 diary 表注释）。
 */
import { Hono, type Context } from 'hono'
import { cors } from 'hono/cors'
import adminHtml from '../public/admin.html'

type Env = {
  DB: D1Database
  PHOTOS: KVNamespace
  /**
   * 共享密钥。**不要写进源码**——这个仓库是公开的，写在这里等于把钥匙给所有人。
   * 用 `npx wrangler secret put APP_KEY` 设置（首次部署时 setup.mjs 会提醒你）。
   */
  APP_KEY: string
}

type Ctx = Context<{ Bindings: Env }>

/**
 * 取服务端配的共享密钥。
 *
 * 从 Cloudflare secret 读，不落源码。Android 端在 `keys.properties`（同样不入库）
 * 里配同一个值，两边必须一致，否则所有请求都是 401。
 */
const appKeyOf = (c: Ctx) => (c.env.APP_KEY ?? '').trim()

const app = new Hono<{ Bindings: Env }>()

app.use(
  '/api/*',
  cors({
    origin: '*',
    allowHeaders: ['Content-Type', 'X-App-Key', 'X-Device-Id', 'X-Identity'],
    allowMethods: ['GET', 'POST', 'PATCH', 'DELETE', 'OPTIONS'],
    maxAge: 86400,
  }),
)

// 健康检查放行，其余都要带对密钥
app.use('/api/*', async (c, next) => {
  if (c.req.path === '/api/health') return next()
  const expected = appKeyOf(c)
  if (!expected) {
    return c.json(
      { error: '服务端还没配置 APP_KEY，请先在 backend 目录执行 npx wrangler secret put APP_KEY' },
      500,
    )
  }
  const key = c.req.header('X-App-Key') ?? c.req.query('key')
  if (key !== expected) return c.json({ error: '密钥不对' }, 401)
  return next()
})

const newId = () => crypto.randomUUID().replaceAll('-', '')

/**
 * 身份名（小江 / 甜甜）：日记和留言的归属只看它。
 *
 * 值是**百分号编码后的 UTF-8**——HTTP 头只能放 ASCII，客户端（OkHttp）直接塞中文会抛异常。
 * 解不开就按原样用（兼容直接塞名字的调用方，比如冒烟脚本里的 ASCII 假身份）。
 */
const identityOf = (c: Ctx) => {
  const raw = (c.req.header('X-Identity') ?? '').trim()
  if (!raw) return ''
  let name = raw
  try {
    name = decodeURIComponent(raw)
  } catch {
    /* 不是合法的百分号编码，原样用 */
  }
  return name.trim().slice(0, 12)
}

/** 装置标识：只用来记「这条写在哪儿」和算未读，不参与归属判断。 */
const deviceOf = (c: Ctx) => (c.req.header('X-Device-Id') ?? '').trim()

app.get('/', (c) => c.text('qlapp backend is running'))
app.get('/api/health', (c) => c.json({ ok: true, ts: Date.now() }))

// 管理网页：纯静态 HTML，由 wrangler 当文本打包进来，随 Worker 一起部署
app.get('/admin', (c) => c.html(adminHtml, 200, { 'Cache-Control': 'no-store' }))

/* ------------------------------ 小游戏最高纪录 ------------------------------ */

const GAMES = ['air', 'piano', 'runner']

type RecordRow = {
  game: string
  score: number
  owner: string
  created_at: number
}

app.get('/api/game-records', async (c) => {
  const { results } = await c.env.DB.prepare(
    'SELECT game, score, owner, created_at FROM game_records ORDER BY game',
  ).all<RecordRow>()
  c.header('Cache-Control', 'no-store')
  return c.json({ records: results })
})

// 网页后台用：直接覆盖某一款的纪录，不做「更高才写」的判断
app.put('/api/game-records/:game', async (c) => {
  const game = c.req.param('game')
  if (!GAMES.includes(game)) return c.json({ error: '没有这款游戏' }, 400)
  const body: { score?: unknown; owner?: unknown } = await c.req.json().catch(() => ({}))
  const score = body.score
  if (
    typeof score !== 'number' ||
    !Number.isInteger(score) ||
    score <= 0 ||
    score > 2147483647
  ) {
    return c.json({ error: '分数要是大于 0 的整数' }, 400)
  }
  const owner = String(body.owner ?? '').trim().slice(0, 32) || '我'
  await c.env.DB.prepare(
    `INSERT INTO game_records (game, score, owner, created_at) VALUES (?, ?, ?, ?)
     ON CONFLICT(game) DO UPDATE SET score = excluded.score, owner = excluded.owner,
       created_at = excluded.created_at`,
  )
    .bind(game, score, owner, Date.now())
    .run()
  return c.json({ ok: true })
})

app.delete('/api/game-records/:game', async (c) => {
  const game = c.req.param('game')
  if (!GAMES.includes(game)) return c.json({ error: '没有这款游戏' }, 400)
  await c.env.DB.prepare('DELETE FROM game_records WHERE game = ?').bind(game).run()
  return c.json({ ok: true })
})

// App 用：只在刷新纪录时才写，靠 ON CONFLICT 的 WHERE 保证「低分不覆盖高分」
app.post('/api/game-records', async (c) => {
  const body: { game?: unknown; score?: unknown; by?: unknown } = await c.req
    .json()
    .catch(() => null)
  if (!body || typeof body !== 'object') return c.json({ error: '成绩格式不正确' }, 400)
  const { game, score, by } = body
  if (
    typeof game !== 'string' ||
    !GAMES.includes(game) ||
    typeof score !== 'number' ||
    !Number.isInteger(score) ||
    score <= 0 ||
    score > 2147483647 ||
    typeof by !== 'string' ||
    !by.trim() ||
    [...by.trim()].length > 32
  ) {
    return c.json({ error: '游戏、分数或昵称不正确' }, 400)
  }
  await c.env.DB.prepare(
    `INSERT INTO game_records (game, score, owner, created_at) VALUES (?, ?, ?, ?)
     ON CONFLICT(game) DO UPDATE SET score=excluded.score, owner=excluded.owner,
       created_at=excluded.created_at WHERE excluded.score > game_records.score`,
  )
    .bind(game, score, by.trim(), Date.now())
    .run()
  const record = await c.env.DB.prepare(
    'SELECT game, score, owner, created_at FROM game_records WHERE game = ?',
  )
    .bind(game)
    .first<RecordRow>()
  c.header('Cache-Control', 'no-store')
  return c.json({ record })
})

/* ------------------------------ 情侣任务 ------------------------------ */

type TaskRow = {
  id: string
  title: string
  note: string
  done: number
  done_by: string | null
  done_at: number | null
  created_by: string
  created_at: number
}

app.get('/api/tasks', async (c) => {
  const { results } = await c.env.DB.prepare(
    `SELECT id, title, note, done, done_by, done_at, created_by, created_at
     FROM tasks ORDER BY done ASC, created_at DESC LIMIT 500`,
  ).all<TaskRow>()
  return c.json({ tasks: results })
})

app.post('/api/tasks', async (c) => {
  const body: { title?: unknown; note?: unknown; by?: unknown } = await c.req
    .json()
    .catch(() => ({}))
  const title = String(body.title ?? '').trim()
  if (!title) return c.json({ error: '任务内容不能为空' }, 400)
  const id = newId()
  const now = Date.now()
  const by = String(body.by ?? '').trim() || '我'
  const note = String(body.note ?? '').slice(0, 500)
  await c.env.DB.prepare(
    `INSERT INTO tasks (id, title, note, done, created_by, created_at)
       VALUES (?, ?, ?, 0, ?, ?)`,
  )
    .bind(id, title.slice(0, 120), note, by.slice(0, 12), now)
    .run()
  return c.json({
    task: {
      id,
      title: title.slice(0, 120),
      note,
      done: 0,
      done_by: null,
      done_at: null,
      created_by: by,
      created_at: now,
    },
  })
})

app.patch('/api/tasks/:id', async (c) => {
  const id = c.req.param('id')
  const body: { done?: unknown; by?: unknown; title?: unknown; note?: unknown } = await c.req
    .json()
    .catch(() => ({}))
  if (typeof body.done === 'boolean') {
    const by = String(body.by ?? '').trim() || '我'
    await c.env.DB.prepare('UPDATE tasks SET done = ?, done_by = ?, done_at = ? WHERE id = ?')
      .bind(
        body.done ? 1 : 0,
        body.done ? by.slice(0, 12) : null,
        body.done ? Date.now() : null,
        id,
      )
      .run()
  }
  if (typeof body.title === 'string' && body.title.trim()) {
    await c.env.DB.prepare('UPDATE tasks SET title = ? WHERE id = ?')
      .bind(body.title.trim().slice(0, 120), id)
      .run()
  }
  if (typeof body.note === 'string') {
    await c.env.DB.prepare('UPDATE tasks SET note = ? WHERE id = ?')
      .bind(body.note.slice(0, 500), id)
      .run()
  }
  return c.json({ ok: true })
})

app.delete('/api/tasks/:id', async (c) => {
  await c.env.DB.prepare('DELETE FROM tasks WHERE id = ?').bind(c.req.param('id')).run()
  return c.json({ ok: true })
})

/* ------------------------------ 情侣相册 ------------------------------ */

// 照片索引里字段名还叫 r2_key（历史遗留：一开始打算用 R2，后来改用 KV，字段没改名）
type PhotoRow = {
  id: string
  caption: string
  uploaded_by: string
  created_at: number
}

const MAX_BYTES = 20 * 1024 * 1024

app.get('/api/photos', async (c) => {
  const { results } = await c.env.DB.prepare(
    `SELECT id, caption, uploaded_by, created_at
     FROM photos ORDER BY created_at DESC LIMIT 300`,
  ).all<PhotoRow>()
  return c.json({ photos: results })
})

app.post('/api/photos', async (c) => {
  const form = await c.req.formData().catch(() => null)
  const file = form?.get('file')
  if (!(file instanceof File)) return c.json({ error: '没有收到图片' }, 400)
  if (file.size > MAX_BYTES) return c.json({ error: '图片超过 20MB' }, 413)
  const caption = String(form?.get('caption') ?? '').slice(0, 200)
  const by = String(form?.get('by') ?? '').trim().slice(0, 12) || '我'
  const id = newId()
  const key = `photos/${id}.jpg`
  await c.env.PHOTOS.put(key, await file.arrayBuffer())
  const now = Date.now()
  await c.env.DB.prepare(
    'INSERT INTO photos (id, r2_key, caption, uploaded_by, created_at) VALUES (?, ?, ?, ?, ?)',
  )
    .bind(id, key, caption, by, now)
    .run()
  return c.json({ photo: { id, caption, uploaded_by: by, created_at: now } })
})

app.get('/api/photos/:id/raw', async (c) => {
  const row = await c.env.DB.prepare('SELECT r2_key FROM photos WHERE id = ?')
    .bind(c.req.param('id'))
    .first<{ r2_key: string }>()
  if (!row) return c.json({ error: '照片不存在' }, 404)
  const buf = await c.env.PHOTOS.get(row.r2_key, 'arrayBuffer')
  if (!buf) return c.json({ error: '图片已丢失' }, 404)
  return new Response(buf, {
    headers: {
      'Content-Type': 'image/jpeg',
      'Cache-Control': 'public, max-age=31536000, immutable',
    },
  })
})

app.delete('/api/photos/:id', async (c) => {
  const id = c.req.param('id')
  const row = await c.env.DB.prepare('SELECT r2_key FROM photos WHERE id = ?')
    .bind(id)
    .first<{ r2_key: string }>()
  if (row) {
    await c.env.PHOTOS.delete(row.r2_key)
    await c.env.DB.prepare('DELETE FROM photos WHERE id = ?').bind(id).run()
  }
  return c.json({ ok: true })
})

app.patch('/api/photos/:id', async (c) => {
  const id = c.req.param('id')
  const body: { caption?: unknown } = await c.req.json().catch(() => ({}))
  await c.env.DB.prepare('UPDATE photos SET caption = ? WHERE id = ?')
    .bind(String(body.caption ?? '').slice(0, 200), id)
    .run()
  return c.json({ ok: true })
})

/* ------------------------------ 情侣日记 ------------------------------ */

const MOODS = ['happy', 'miss', 'calm', 'sad', 'angry']
const MOOD_SET = new Set(MOODS)
const MAX_CONTENT = 2000

const DATE_RE = /^\d{4}-\d{2}-\d{2}$/
const isDate = (v: unknown): v is string => typeof v === 'string' && DATE_RE.test(v)

type DiaryRow = {
  entry_date: string
  author: string
  mood: string
  content: string
  device_id: string
  updated_at: number
}

/** 归属靠 author 判断，所以每条都回一个 `mine`，省得客户端自己比字符串。 */
const diaryRow = (r: DiaryRow, me: string) => ({
  date: r.entry_date,
  author: r.author,
  mood: r.mood,
  content: r.content,
  deviceId: r.device_id,
  updatedAt: r.updated_at,
  mine: me !== '' && r.author === me,
})

const DIARY_COLS = 'entry_date, author, mood, content, device_id, updated_at'

app.get('/api/diary', async (c) => {
  const me = identityOf(c)
  // 网页后台要全量，App 只要一个月
  if (c.req.query('all') === '1') {
    const { results } = await c.env.DB.prepare(
      `SELECT ${DIARY_COLS} FROM diary ORDER BY entry_date`,
    ).all<DiaryRow>()
    c.header('Cache-Control', 'no-store')
    return c.json({ entries: results.map((r) => diaryRow(r, me)) })
  }
  const month = c.req.query('month') ?? ''
  if (!/^\d{4}-\d{2}$/.test(month)) {
    return c.json({ error: '月份格式应该是 2026-09' }, 400)
  }
  const { results } = await c.env.DB.prepare(
    `SELECT ${DIARY_COLS} FROM diary WHERE entry_date LIKE ? ORDER BY entry_date`,
  )
    .bind(`${month}-%`)
    .all<DiaryRow>()
  c.header('Cache-Control', 'no-store')
  return c.json({ entries: results.map((r) => diaryRow(r, me)) })
})

app.get('/api/diary/:date', async (c) => {
  const date = c.req.param('date')
  if (!isDate(date)) return c.json({ error: '日期格式应该是 2026-09-27' }, 400)
  const me = identityOf(c)
  const { results } = await c.env.DB.prepare(
    `SELECT ${DIARY_COLS} FROM diary WHERE entry_date = ? ORDER BY updated_at`,
  )
    .bind(date)
    .all<DiaryRow>()
  c.header('Cache-Control', 'no-store')
  return c.json({ entries: results.map((r) => diaryRow(r, me)) })
})

/**
 * 写日记。同一天同一个身份再写就是改，靠主键 (entry_date, author) 走 ON CONFLICT 覆盖。
 *
 * 归属取 `X-Identity`，取不到才回落到 body.by —— 网页后台没有身份头，
 * 所以它能用 `?author=` 指定「代谁写」；App 不带这个参数，一律写自己的。
 */
app.put('/api/diary/:date', async (c) => {
  const date = c.req.param('date')
  if (!isDate(date)) return c.json({ error: '日期格式应该是 2026-09-27' }, 400)
  const body: { mood?: unknown; content?: unknown; by?: unknown } = await c.req
    .json()
    .catch(() => ({}))
  const override = (c.req.query('author') ?? '').trim().slice(0, 12)
  const author = override || identityOf(c) || String(body.by ?? '').trim().slice(0, 12)
  if (!author) return c.json({ error: '缺少身份' }, 400)
  if (!MOOD_SET.has(String(body.mood ?? ''))) return c.json({ error: '请先选一个心情' }, 400)
  const content = String(body.content ?? '').slice(0, MAX_CONTENT)
  const deviceId = deviceOf(c)
  const now = Date.now()
  await c.env.DB.prepare(
    `INSERT INTO diary (entry_date, author, mood, content, device_id, updated_at)
     VALUES (?, ?, ?, ?, ?, ?)
     ON CONFLICT(entry_date, author) DO UPDATE SET
       mood = excluded.mood, content = excluded.content,
       device_id = excluded.device_id, updated_at = excluded.updated_at`,
  )
    .bind(date, author, body.mood, content, deviceId, now)
    .run()
  return c.json({
    entry: { date, author, mood: body.mood, content, deviceId, updatedAt: now, mine: true },
  })
})

/**
 * 删日记。带 `?author=` 是网页后台在删指定的人，不带就删自己的。
 *
 * 老版本 App 还没有 `X-Identity` 头，所以最后退回「按装置删」——
 * 只是为了让升级期间旧包仍能删掉自己写的那条，新包一律走身份名。
 */
app.delete('/api/diary/:date', async (c) => {
  const date = c.req.param('date')
  if (!isDate(date)) return c.json({ error: '日期格式应该是 2026-09-27' }, 400)
  const target = (c.req.query('author') ?? '').trim().slice(0, 12)
  if (target) {
    await c.env.DB.prepare('DELETE FROM diary WHERE entry_date = ? AND author = ?')
      .bind(date, target)
      .run()
    return c.json({ ok: true })
  }
  const me = identityOf(c)
  if (me) {
    await c.env.DB.prepare('DELETE FROM diary WHERE entry_date = ? AND author = ?')
      .bind(date, me)
      .run()
    return c.json({ ok: true })
  }
  const deviceId = deviceOf(c)
  if (!deviceId) return c.json({ error: '缺少身份' }, 400)
  await c.env.DB.prepare('DELETE FROM diary WHERE entry_date = ? AND device_id = ?')
    .bind(date, deviceId)
    .run()
  return c.json({ ok: true })
})

/* ------------------------------ 留言墙 ------------------------------ */

const MAX_MESSAGE = 500

type MessageRow = {
  id: string
  device_id: string
  author: string
  content: string
  created_at: number
}

const messageRow = (r: MessageRow, me: string) => ({
  id: r.id,
  deviceId: r.device_id,
  author: r.author,
  content: r.content,
  createdAt: r.created_at,
  mine: me !== '' && r.author === me,
})

app.get('/api/messages', async (c) => {
  const me = identityOf(c)
  const deviceId = deviceOf(c)
  const { results } = await c.env.DB.prepare(
    `SELECT id, device_id, author, content, created_at
       FROM messages ORDER BY created_at DESC LIMIT 300`,
  ).all<MessageRow>()
  // 未读 = 上次看过之后、别人写的。归属按身份名判，身份拿不到才退回装置。
  let unread = 0
  if (deviceId) {
    const seen = await c.env.DB.prepare('SELECT read_at FROM wall_reads WHERE device_id = ?')
      .bind(deviceId)
      .first<{ read_at: number }>()
    const since = seen?.read_at ?? 0
    unread = results.filter(
      (r) => r.created_at > since && (me ? r.author !== me : r.device_id !== deviceId),
    ).length
  }
  c.header('Cache-Control', 'no-store')
  return c.json({ messages: results.map((r) => messageRow(r, me)), unread })
})

app.post('/api/messages', async (c) => {
  const deviceId = deviceOf(c)
  if (!deviceId || deviceId.length > 64) return c.json({ error: '缺少装置标识' }, 400)
  const body: { content?: unknown; by?: unknown } = await c.req.json().catch(() => ({}))
  const content = String(body.content ?? '').trim()
  if (!content) return c.json({ error: '留言不能为空' }, 400)
  const author = identityOf(c) || String(body.by ?? '').trim().slice(0, 12) || '我'
  const id = newId()
  const now = Date.now()
  await c.env.DB.prepare(
    'INSERT INTO messages (id, device_id, author, content, created_at) VALUES (?, ?, ?, ?, ?)',
  )
    .bind(id, deviceId, author, content.slice(0, MAX_MESSAGE), now)
    .run()
  return c.json({
    message: {
      id,
      deviceId,
      author,
      content: content.slice(0, MAX_MESSAGE),
      createdAt: now,
      mine: true,
    },
  })
})

// 下面两个接口 App 里没有入口（留言墙上不许改也不许删），只给网页后台用
app.patch('/api/messages/:id', async (c) => {
  const id = c.req.param('id')
  if (!id) return c.json({ error: '缺少留言 ID' }, 400)
  const body: { content?: unknown } = await c.req.json().catch(() => ({}))
  const content = String(body.content ?? '').trim()
  if (!content) return c.json({ error: '留言不能为空' }, 400)
  await c.env.DB.prepare('UPDATE messages SET content = ? WHERE id = ?')
    .bind(content.slice(0, MAX_MESSAGE), id)
    .run()
  return c.json({ ok: true })
})

app.delete('/api/messages/:id', async (c) => {
  const id = c.req.param('id')
  if (!id) return c.json({ error: '缺少留言 ID' }, 400)
  await c.env.DB.prepare('DELETE FROM messages WHERE id = ?').bind(id).run()
  return c.json({ ok: true })
})

// 把「留言墙已读到此刻」记下来。已读是「人」的属性，所以单独一张表、按装置记。
app.post('/api/messages/read', async (c) => {
  const deviceId = deviceOf(c)
  if (!deviceId) return c.json({ error: '缺少装置标识' }, 400)
  await c.env.DB.prepare(
    `INSERT INTO wall_reads (device_id, read_at) VALUES (?, ?)
     ON CONFLICT(device_id) DO UPDATE SET read_at = excluded.read_at`,
  )
    .bind(deviceId, Date.now())
    .run()
  return c.json({ ok: true })
})

/* ------------------------------ 设置（昵称 / 纪念日） ------------------------------ */

const NICK_PREFIX = 'nickname:'
const ANNIVERSARY_KEY = 'anniversary'

app.get('/api/settings', async (c) => {
  const { results } = await c.env.DB.prepare('SELECT key, value FROM settings').all<{
    key: string
    value: string
  }>()
  const nicknames: Record<string, string> = {}
  let anniversary: string | null = null
  for (const row of results) {
    if (row.key.startsWith(NICK_PREFIX)) {
      nicknames[row.key.slice(NICK_PREFIX.length)] = row.value
    } else if (row.key === ANNIVERSARY_KEY) {
      anniversary = row.value
    }
  }
  // 网页后台要按「人」改昵称，所以除了设置表里记过的装置，还得把写过东西的装置也列出来
  const devices = new Set(Object.keys(nicknames))
  const { results: rows } = await c.env.DB.prepare(
    `SELECT device_id FROM diary
      UNION SELECT device_id FROM messages`,
  ).all<{ device_id: string }>()
  for (const r of rows) if (r.device_id) devices.add(r.device_id)
  c.header('Cache-Control', 'no-store')
  return c.json({
    anniversary: anniversary === null ? null : Number(anniversary),
    nicknames,
    devices: [...devices],
  })
})

app.put('/api/settings', async (c) => {
  const body: { nicknames?: unknown; anniversary?: unknown } = await c.req
    .json()
    .catch(() => ({}))
  const now = Date.now()
  const stmts: D1PreparedStatement[] = []
  const upsert = (key: string, value: string) =>
    c.env.DB.prepare(
      `INSERT INTO settings (key, value, updated_at) VALUES (?, ?, ?)
       ON CONFLICT(key) DO UPDATE SET value = excluded.value, updated_at = excluded.updated_at`,
    ).bind(key, value, now)
  if (body.nicknames && typeof body.nicknames === 'object') {
    for (const [device, rawName] of Object.entries(body.nicknames as Record<string, unknown>)) {
      const deviceId = device.trim()
      if (!deviceId || deviceId.length > 64) return c.json({ error: '装置标识不正确' }, 400)
      const name = String(rawName ?? '').trim()
      if (!name) return c.json({ error: '昵称不能为空' }, 400)
      stmts.push(upsert(NICK_PREFIX + deviceId, [...name].slice(0, 12).join('')))
    }
  }
  if (body.anniversary !== undefined && body.anniversary !== null) {
    const num = Number(body.anniversary)
    if (!Number.isFinite(num) || num < 0) return c.json({ error: '纪念日不正确' }, 400)
    stmts.push(upsert(ANNIVERSARY_KEY, String(Math.trunc(num))))
  }
  if (stmts.length === 0) return c.json({ error: '没有要改的设置' }, 400)
  await c.env.DB.batch(stmts)
  return c.json({ ok: true })
})

/* ------------------------------ 认领旧数据（已停用） ------------------------------ */

/**
 * 这个接口**不再做任何事**，只为了让老版本 App 不收到 404 而留着。
 *
 * 它原本的作用是：按作者名，把「名字对得上、装置 ID 对不上」的留言和日记改挂到本机。
 * 那是在「归属看 device_id」的前提下打的补丁 —— 可装置 ID 由 ANDROID_ID 派生、重装不变，
 * 于是一台手机重装一次就按名字搬一次：先选「小江」搬一遍，再重装选「甜甜」又搬一遍，
 * 两个人的数据全被塞进同一个 device_id，界面上统统显示成「我」。
 *
 * 现在归属直接看 author（身份名），搬不搬都一样，所以这段逻辑彻底多余。
 */
app.post('/api/claim', (c) => c.json({ ok: true, messages: 0, diaryClaimed: 0, diaryMerged: 0 }))

/* ------------------------------ 五子棋 ------------------------------ */

const GOMOKU_SIZE = 15

type GomokuRow = {
  id: string
  black_id: string
  black_name: string
  white_id: string | null
  white_name: string
  status: string
  turn: string
  moves: string
  winner: string | null
  created_at: number
  updated_at: number
}

/** `"7,7;8,8"` → `[[7,7],[8,8]]`，顺手把越界的点滤掉。 */
const parseMoves = (s: string) =>
  s
    .split(';')
    .filter(Boolean)
    .map((part) => part.split(',').map(Number))
    .filter(
      ([x, y]) =>
        Number.isInteger(x) &&
        Number.isInteger(y) &&
        x >= 0 &&
        x < GOMOKU_SIZE &&
        y >= 0 &&
        y < GOMOKU_SIZE,
    )

const serializeMoves = (moves: number[][]) => moves.map(([x, y]) => `${x},${y}`).join(';')

/** 只检查最后一手所在的四条线 —— 前面几手在落的时候就查过了，赢不了。 */
function gomokuWins(moves: number[][]): boolean {
  if (moves.length === 0) return false
  const side = moves.length % 2 === 1 ? 1 : 2
  const board = new Map<string, number>()
  moves.forEach(([x, y], i) => board.set(`${x},${y}`, i % 2 === 0 ? 1 : 2))
  const [lx, ly] = moves[moves.length - 1]
  const dirs = [
    [1, 0],
    [0, 1],
    [1, 1],
    [1, -1],
  ]
  for (const [dx, dy] of dirs) {
    let count = 1
    for (const sign of [1, -1]) {
      let x = lx + dx * sign
      let y = ly + dy * sign
      while (board.get(`${x},${y}`) === side) {
        count++
        x += dx * sign
        y += dy * sign
      }
    }
    if (count >= 5) return true
  }
  return false
}

const gomokuRow = (r: GomokuRow) => ({
  id: r.id,
  blackId: r.black_id,
  blackName: r.black_name ?? '',
  whiteId: r.white_id ?? null,
  whiteName: r.white_name ?? '',
  status: r.status,
  turn: r.turn,
  moves: r.moves ?? '',
  winner: r.winner ?? null,
  createdAt: r.created_at,
  updatedAt: r.updated_at,
})

app.get('/api/gomoku/current', async (c) => {
  const me = deviceOf(c)
  if (!me || me.length > 64) return c.json({ error: '缺少装置标识' }, 400)
  // 我参与的局，或者还挂在桌上等接的局
  const row = await c.env.DB.prepare(
    `SELECT * FROM gomoku_matches
    WHERE black_id = ? OR white_id = ? OR (status = 'waiting' AND black_id != ?)
    ORDER BY created_at DESC LIMIT 1`,
  )
    .bind(me, me, me)
    .first<GomokuRow>()
  // 战绩现算，不存表。
  // ⚠️ 三项必须各自直接数，且整段限定「我参与过的局」——
  // 用「总数 − 和棋 − 我胜」推对方胜的话，只要库里有我没参与的已结束局
  // （联调假装置留下的行），差额就全算到对方头上，表现为「刚进游戏对方就赢了 N 回」。
  const tally = await c.env.DB.prepare(
    `SELECT
      SUM(CASE WHEN (winner = 'black' AND black_id = ?)
                OR (winner = 'white' AND white_id = ?) THEN 1 ELSE 0 END) AS mine,
      SUM(CASE WHEN (winner = 'white' AND black_id = ?)
                OR (winner = 'black' AND white_id = ?) THEN 1 ELSE 0 END) AS theirs,
      SUM(CASE WHEN winner = 'draw' THEN 1 ELSE 0 END) AS draws
    FROM gomoku_matches
    WHERE status = 'finished' AND (black_id = ? OR white_id = ?)`,
  )
    .bind(me, me, me, me, me, me)
    .first<{ mine: number | null; theirs: number | null; draws: number | null }>()
  const mine = Number(tally?.mine ?? 0)
  const theirs = Number(tally?.theirs ?? 0)
  const draws = Number(tally?.draws ?? 0)
  c.header('Cache-Control', 'no-store')
  return c.json({ match: row ? gomokuRow(row) : null, stats: { mine, theirs, draws } })
})

/** 发起一局。已经有一局挂着就复用，不会开出第二桌。 */
app.post('/api/gomoku', async (c) => {
  const me = deviceOf(c)
  if (!me || me.length > 64) return c.json({ error: '缺少装置标识' }, 400)
  const body: { by?: unknown } = await c.req.json().catch(() => ({}))
  const name = typeof body.by === 'string' ? body.by.trim().slice(0, 12) : ''
  const active = await c.env.DB.prepare(
    `SELECT * FROM gomoku_matches
    WHERE status IN ('waiting', 'playing')
      AND (black_id = ? OR white_id = ? OR (status = 'waiting' AND black_id != ?))
    ORDER BY created_at DESC LIMIT 1`,
  )
    .bind(me, me, me)
    .first<GomokuRow>()
  if (active) return c.json({ ok: true, reused: true, match: gomokuRow(active) })
  const now = Date.now()
  const id = newId()
  await c.env.DB.prepare(
    `INSERT INTO gomoku_matches
      (id, black_id, black_name, white_id, white_name, status, turn, moves, winner,
       created_by, created_at, updated_at)
    VALUES (?, ?, ?, NULL, '', 'waiting', 'black', '', NULL, ?, ?, ?)`,
  )
    .bind(id, me, name, me, now, now)
    .run()
  const row = await c.env.DB.prepare('SELECT * FROM gomoku_matches WHERE id = ?')
    .bind(id)
    .first<GomokuRow>()
  return c.json({ ok: true, match: gomokuRow(row!) })
})

app.post('/api/gomoku/:id/join', async (c) => {
  const me = deviceOf(c)
  if (!me || me.length > 64) return c.json({ error: '缺少装置标识' }, 400)
  const id = c.req.param('id')
  const body: { by?: unknown } = await c.req.json().catch(() => ({}))
  const name = typeof body.by === 'string' ? body.by.trim().slice(0, 12) : ''
  const row = await c.env.DB.prepare('SELECT * FROM gomoku_matches WHERE id = ?')
    .bind(id)
    .first<GomokuRow>()
  if (!row) return c.json({ error: '找不到这一局' }, 404)
  if (row.status !== 'waiting') return c.json({ error: '这局已经开始了' }, 409)
  if (row.black_id === me) return c.json({ error: '这局是你发起的，等对方来' }, 409)
  // 乐观锁：只有还挂着、还没人接的时候才写得进去，两边同时点只有一个能成
  const res = await c.env.DB.prepare(
    `UPDATE gomoku_matches
    SET white_id = ?, white_name = ?, status = 'playing', turn = 'black', updated_at = ?
    WHERE id = ? AND status = 'waiting' AND white_id IS NULL`,
  )
    .bind(me, name, Date.now(), id)
    .run()
  if (!res.meta.changes) return c.json({ error: '这局已经有人接了' }, 409)
  const fresh = await c.env.DB.prepare('SELECT * FROM gomoku_matches WHERE id = ?')
    .bind(id)
    .first<GomokuRow>()
  return c.json({ ok: true, match: gomokuRow(fresh!) })
})

app.delete('/api/gomoku/:id', async (c) => {
  const id = c.req.param('id')
  const res = await c.env.DB.prepare(
    "DELETE FROM gomoku_matches WHERE id = ? AND status = 'waiting'",
  )
    .bind(id)
    .run()
  if (!res.meta.changes) return c.json({ error: '这局已经在下，删不掉' }, 409)
  return c.json({ ok: true })
})

app.post('/api/gomoku/:id/move', async (c) => {
  const me = deviceOf(c)
  if (!me || me.length > 64) return c.json({ error: '缺少装置标识' }, 400)
  const id = c.req.param('id')
  const body: { x?: unknown; y?: unknown } = await c.req.json().catch(() => ({}))
  const { x, y } = body
  if (
    typeof x !== 'number' ||
    typeof y !== 'number' ||
    !Number.isInteger(x) ||
    !Number.isInteger(y) ||
    x < 0 ||
    x >= GOMOKU_SIZE ||
    y < 0 ||
    y >= GOMOKU_SIZE
  ) {
    return c.json({ error: '落子位置不合法' }, 400)
  }
  const row = await c.env.DB.prepare('SELECT * FROM gomoku_matches WHERE id = ?')
    .bind(id)
    .first<GomokuRow>()
  if (!row) return c.json({ error: '找不到这一局' }, 404)
  if (row.status !== 'playing') return c.json({ error: '这局已经结束了' }, 409)
  const side = row.turn === 'white' ? 'white' : 'black'
  const mySide = row.black_id === me ? 'black' : row.white_id === me ? 'white' : null
  if (mySide === null) return c.json({ error: '你不在这一局里' }, 403)
  if (mySide !== side) return c.json({ error: '还没轮到你' }, 409)
  const moves = parseMoves(String(row.moves ?? ''))
  if (moves.some(([mx, my]) => mx === x && my === y)) {
    return c.json({ error: '这个点已经有子了' }, 409)
  }
  const next = [...moves, [x, y]]
  const won = gomokuWins(next)
  const full = next.length >= GOMOKU_SIZE * GOMOKU_SIZE
  const status = won || full ? 'finished' : 'playing'
  const winner = won ? side : full ? 'draw' : null
  const turn = status === 'playing' ? (side === 'black' ? 'white' : 'black') : side
  const now = Date.now()
  // 乐观锁：把「落子前的手数 + 轮到谁」当版本号，两边同时落只有一个能成
  const res = await c.env.DB.prepare(
    `UPDATE gomoku_matches
    SET moves = ?, turn = ?, status = ?, winner = ?, updated_at = ?
    WHERE id = ? AND status = 'playing' AND turn = ? AND moves = ?`,
  )
    .bind(serializeMoves(next), turn, status, winner, now, id, side, String(row.moves ?? ''))
    .run()
  if (!res.meta.changes) return c.json({ error: '这手没落上，刷新一下再来' }, 409)
  const fresh = await c.env.DB.prepare('SELECT * FROM gomoku_matches WHERE id = ?')
    .bind(id)
    .first<GomokuRow>()
  return c.json({ ok: true, match: gomokuRow(fresh!) })
})

app.post('/api/gomoku/:id/resign', async (c) => {
  const me = deviceOf(c)
  if (!me || me.length > 64) return c.json({ error: '缺少装置标识' }, 400)
  const id = c.req.param('id')
  const row = await c.env.DB.prepare('SELECT * FROM gomoku_matches WHERE id = ?')
    .bind(id)
    .first<GomokuRow>()
  if (!row) return c.json({ error: '找不到这一局' }, 404)
  if (row.status !== 'playing') return c.json({ error: '这局已经结束了' }, 409)
  const mySide = row.black_id === me ? 'black' : row.white_id === me ? 'white' : null
  if (mySide === null) return c.json({ error: '你不在这一局里' }, 403)
  const res = await c.env.DB.prepare(
    `UPDATE gomoku_matches SET status = 'finished', winner = ?, updated_at = ?
    WHERE id = ? AND status = 'playing'`,
  )
    .bind(mySide === 'black' ? 'white' : 'black', Date.now(), id)
    .run()
  if (!res.meta.changes) return c.json({ error: '这局已经结束了' }, 409)
  const fresh = await c.env.DB.prepare('SELECT * FROM gomoku_matches WHERE id = ?')
    .bind(id)
    .first<GomokuRow>()
  return c.json({ ok: true, match: gomokuRow(fresh!) })
})

/**
 * 再来一局。**黑白对调**，直接进 playing。
 * 不用 POST /api/gomoku 是因为那个接口让发起者永远执黑，连下几局会一直是同一个人先手。
 */
app.post('/api/gomoku/:id/rematch', async (c) => {
  const me = deviceOf(c)
  if (!me || me.length > 64) return c.json({ error: '缺少装置标识' }, 400)
  const id = c.req.param('id')
  const body: { by?: unknown } = await c.req.json().catch(() => ({}))
  const name = typeof body.by === 'string' ? body.by.trim().slice(0, 12) : ''
  const active = await c.env.DB.prepare(
    `SELECT * FROM gomoku_matches
    WHERE status IN ('waiting', 'playing') AND (black_id = ? OR white_id = ?)
    ORDER BY created_at DESC LIMIT 1`,
  )
    .bind(me, me)
    .first<GomokuRow>()
  if (active) return c.json({ ok: true, reused: true, match: gomokuRow(active) })
  const prev = await c.env.DB.prepare('SELECT * FROM gomoku_matches WHERE id = ?')
    .bind(id)
    .first<GomokuRow>()
  if (!prev) return c.json({ error: '找不到上一局' }, 404)
  if (prev.status !== 'finished') return c.json({ error: '这局还没结束' }, 409)
  const prevBlackId = String(prev.black_id ?? '')
  const prevWhiteId = String(prev.white_id ?? '')
  if (prevBlackId !== me && prevWhiteId !== me) return c.json({ error: '你不在这一局里' }, 403)
  if (!prevWhiteId) return c.json({ error: '上一局没有对手，开不了新局' }, 409)
  const prevBlackName = String(prev.black_name ?? '') || (prevBlackId === me ? name : '')
  const prevWhiteName = String(prev.white_name ?? '') || (prevWhiteId === me ? name : '')
  const now = Date.now()
  const fresh = newId()
  await c.env.DB.prepare(
    `INSERT INTO gomoku_matches
      (id, black_id, black_name, white_id, white_name, status, turn, moves, winner,
       created_by, created_at, updated_at)
    VALUES (?, ?, ?, ?, ?, 'playing', 'black', '', NULL, ?, ?, ?)`,
  )
    .bind(
      // 黑白对调：上一局执白的人这局执黑
      fresh,
      prevWhiteId,
      prevWhiteName,
      prevBlackId,
      prevBlackName,
      me,
      now,
      now,
    )
    .run()
  const row = await c.env.DB.prepare('SELECT * FROM gomoku_matches WHERE id = ?')
    .bind(fresh)
    .first<GomokuRow>()
  return c.json({ ok: true, match: gomokuRow(row!) })
})

export default app
