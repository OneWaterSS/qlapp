import { after, before, test } from 'node:test'
import assert from 'node:assert/strict'
import { readFile, mkdir } from 'node:fs/promises'
import { build } from 'esbuild'
import { Miniflare, convertV4MiniflareOptions } from 'miniflare'

let worker
// 测试用密钥：优先读环境变量，没有就用固定的 test-key（只在本机 Miniflare 里生效，不是真实密钥）
const APP_KEY = process.env.APP_KEY || 'test-key'
const headers = { 'X-App-Key': APP_KEY, 'Content-Type': 'application/json' }
const submit = (game, score, by) => worker.dispatchFetch('https://local.test/api/game-records', {
  method: 'POST', headers, body: JSON.stringify({ game, score, by }),
})
const list = async () => (await worker.dispatchFetch('https://local.test/api/game-records', { headers })).json()

before(async () => {
  await mkdir('.wrangler/record-tests', { recursive: true })
  await build({ entryPoints: ['src/index.ts'], outfile: '.wrangler/record-tests/worker.js', bundle: true, format: 'esm', platform: 'browser' })
  worker = new Miniflare(convertV4MiniflareOptions({ modules: true, scriptPath: '.wrangler/record-tests/worker.js',
    compatibilityDate: '2026-09-01', d1Databases: ['DB'], kvNamespaces: ['PHOTOS'],
    bindings: { APP_KEY } }))
  const db = await worker.getD1Database('DB')
  await db.prepare(await readFile('migrations/0001_game_records.sql', 'utf8')).run()
})
after(async () => { await worker?.dispose() })

test('shared records: auth, validation, independent games and atomic highest score', async () => {
  assert.equal((await worker.dispatchFetch('https://local.test/api/game-records')).status, 401)
  assert.deepEqual((await list()).records, [])
  for (const payload of [null, [], { game: 'bad', score: 1, by: 'A' },
    { game: 'air', score: -1, by: 'A' }, { game: 'air', score: 1.5, by: 'A' },
    { game: 'air', score: 2147483648, by: 'A' }, { game: 'air', score: 1, by: ' ' }]) {
    assert.equal((await worker.dispatchFetch('https://local.test/api/game-records', {
      method: 'POST', headers, body: JSON.stringify(payload),
    })).status, 400)
  }
  assert.equal((await submit('air', 100, '小江')).status, 200)
  assert.equal((await submit('air', 250, '甜甜')).status, 200)
  let record = (await list()).records.find(r => r.game === 'air')
  assert.equal(record.score, 250)
  assert.equal(record.owner, '甜甜')
  const firstTime = record.created_at
  await submit('air', 100, '小江') // delayed/offline lower score
  await submit('air', 250, '平分玩家')
  record = (await list()).records.find(r => r.game === 'air')
  assert.equal(record.owner, '甜甜')
  assert.equal(record.created_at, firstTime)
  await submit('piano', 500, '琴键玩家')
  await submit('runner', 700, '跑酷玩家')
  await Promise.all(Array.from({ length: 20 }, (_, i) => submit('air', 300 + i, `玩家${i}`)))
  const records = (await list()).records
  assert.equal(records.length, 3)
  assert.equal(records.find(r => r.game === 'air').score, 319)
  assert.equal(records.find(r => r.game === 'air').owner, '玩家19')
  assert.equal(records.find(r => r.game === 'piano').owner, '琴键玩家')
  assert.equal(records.find(r => r.game === 'runner').score, 700)
  assert.equal((await worker.dispatchFetch('https://local.test/api/game-records', { headers })).headers.get('cache-control'), 'no-store')
})
