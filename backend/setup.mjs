#!/usr/bin/env node
/**
 * 一键搭建后端
 * 用法：cd backend && npm install && node setup.mjs
 *
 * 会依次做：
 *   1. 检查 Cloudflare 登录状态
 *   2. 准备 wrangler.toml（不存在就从 wrangler.toml.example 拷一份）
 *   3. 建 D1 数据库 → 把 database_id 写进 wrangler.toml
 *   4. 建 KV 命名空间（存照片）→ 把 id 写进 wrangler.toml
 *   5. 建表
 *   6. 部署 Worker
 *   7. 生成并设置共享密钥 APP_KEY
 *
 * 重复运行是安全的：已经建过的东西会跳过或复用，不会重复创建。
 */
import { execSync } from 'node:child_process'
import { randomBytes } from 'node:crypto'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const ROOT = path.dirname(fileURLToPath(import.meta.url))
const TOML = path.join(ROOT, 'wrangler.toml')
const TOML_EXAMPLE = path.join(ROOT, 'wrangler.toml.example')

function sh(cmd) {
  try {
    return execSync(cmd, {
      cwd: ROOT,
      encoding: 'utf8',
      stdio: ['ignore', 'pipe', 'pipe'],
      shell: true,
    }) || ''
  } catch (e) {
    return `${e.stdout || ''}\n${e.stderr || ''}`
  }
}

/** 和 sh 一样，但把 input 喂给命令的 stdin（wrangler secret put 要用）。 */
function shIn(cmd, input) {
  try {
    return execSync(cmd, {
      cwd: ROOT,
      encoding: 'utf8',
      input,
      stdio: ['pipe', 'pipe', 'pipe'],
      shell: true,
    }) || ''
  } catch (e) {
    return `${e.stdout || ''}\n${e.stderr || ''}`
  }
}

const TOTAL = 7
const step = (n, msg) => console.log(`\n[${n}/${TOTAL}] ${msg}`)
const fail = (msg) => {
  console.log(`\n!! ${msg}`)
  process.exit(1)
}

console.log('=== qlapp 后端一键搭建 ===')

/* ---------------- 1. 检查登录 ---------------- */
step(1, '检查 Cloudflare 登录状态…')
const who = sh('npx wrangler whoami')
if (/not logged in|You are not|not authenticated|not authorized/i.test(who)) {
  fail(
    '还没登录 Cloudflare。请先执行：\n\n' +
    '    cd backend\n' +
    '    npx wrangler login\n\n' +
    '浏览器会弹出授权页面，确认后再重新运行 node setup.mjs',
  )
}
const account = who.match(/\S+@\S+\.\S+/)?.[0]
console.log(account ? `已登录：${account}` : '已登录')

/* ---------------- 2. 准备 wrangler.toml ---------------- */
step(2, '准备 wrangler.toml…')
if (!fs.existsSync(TOML)) {
  if (!fs.existsSync(TOML_EXAMPLE)) fail('找不到 wrangler.toml.example，仓库不完整')
  fs.copyFileSync(TOML_EXAMPLE, TOML)
  console.log('wrangler.toml 不存在，已从 wrangler.toml.example 生成')
} else {
  console.log('已存在，沿用（里面的数据库 id 会在下面自动补齐）')
}

/* ---------------- 3. 建 D1 数据库 ---------------- */
step(3, '创建 D1 数据库 qlapp-db…')
let dbId = ''
const createOut = sh('npx wrangler d1 create qlapp-db')
dbId = createOut.match(/database_id\s*=\s*"([^"]+)"/)?.[1] || ''

if (!dbId) {
  // 多半是已经建过了，从列表里取
  console.log('（可能已经建过，改为从列表查找）')
  const listJson = sh('npx wrangler d1 list --json')
  try {
    const rows = JSON.parse(listJson.slice(listJson.indexOf('[')))
    dbId = rows.find((r) => r.name === 'qlapp-db')?.uuid || ''
  } catch {
    dbId = ''
  }
}
if (!dbId) fail('没能拿到 database_id。手动执行 npx wrangler d1 list 看看，然后把 id 填进 wrangler.toml')
console.log(`database_id = ${dbId}`)

let toml = fs.readFileSync(TOML, 'utf8')
toml = toml.replace(/database_id\s*=\s*"[^"]*"/, `database_id = "${dbId}"`)
fs.writeFileSync(TOML, toml, 'utf8')
console.log('已写回 wrangler.toml')

/* ---------------- 4. 建 KV 命名空间（存照片） ---------------- */
step(4, '创建 KV 命名空间（存照片）…')
toml = fs.readFileSync(TOML, 'utf8')
if (!toml.includes('KV_ID_PLACEHOLDER')) {
  console.log('（KV 已配置，跳过）')
} else {
  const kvOut = sh('npx wrangler kv namespace create PHOTOS')
  let kvId = kvOut.match(/id\s*=\s*"([^"]+)"/)?.[1] || ''
  if (!kvId) {
    const listJson = sh('npx wrangler kv namespace list')
    try {
      const rows = JSON.parse(listJson.slice(listJson.indexOf('[')))
      kvId = rows.find((r) => r.title?.endsWith('-PHOTOS'))?.id || ''
    } catch {
      kvId = ''
    }
  }
  if (!kvId)
    fail('没能拿到 KV id。手动执行 npx wrangler kv namespace list，把 PHOTOS 的 id 填进 wrangler.toml')
  toml = toml.replace('KV_ID_PLACEHOLDER', kvId)
  fs.writeFileSync(TOML, toml, 'utf8')
  console.log(`kv id = ${kvId}`)
}

/* ---------------- 5. 建表 ---------------- */
step(5, '初始化数据表…')
const sql = fs.readFileSync(path.join(ROOT, 'schema.sql'), 'utf8')
fs.writeFileSync(path.join(ROOT, '_tmp_init.sql'), sql, 'utf8')
const initOut = sh('npx wrangler d1 execute qlapp-db --file=./_tmp_init.sql --remote')
fs.unlinkSync(path.join(ROOT, '_tmp_init.sql'))
if (/error|Error/.test(initOut)) {
  console.log(initOut)
  fail('建表出问题了，把上面这段报错发我')
}
console.log('表已就绪')

/* ---------------- 6. 部署 ---------------- */
step(6, '部署 Worker…')
const deployOut = sh('npx wrangler deploy')
const url = deployOut.match(/https:\/\/[^\s"'<>]+\.workers\.dev/)?.[0]
if (!url) {
  console.log('部署没拿到地址，原始输出如下：')
  console.log(deployOut)
  fail('部署可能失败了')
}
console.log(`已部署：${url}`)

/* ---------------- 7. 设置共享密钥 ---------------- */
step(7, '设置共享密钥 APP_KEY…')
// 密钥不进源码库（仓库是公开的），只存在 Cloudflare secret 和你本地的 keys.properties
let already = false
try {
  const listJson = sh('npx wrangler secret list')
  const rows = JSON.parse(listJson.slice(listJson.indexOf('[')))
  already = rows.some((r) => r.name === 'APP_KEY')
} catch {
  already = false
}

if (already) {
  console.log('（APP_KEY 已设置过，保持不变）')
  console.log('  如果你手上没有这个值了，先 npx wrangler secret delete APP_KEY 再重跑本脚本')
  console.log('  注意：换密钥后所有已装的 App 都要重新填新密钥，否则连不上')
} else {
  const key = process.env.APP_KEY || `qlapp-${randomBytes(16).toString('hex')}`
  const out = shIn('npx wrangler secret put APP_KEY', key)
  if (/error|Error/i.test(out)) {
    console.log(out)
    fail('设置密钥失败')
  }
  console.log('已设置。把下面这一行抄进项目根目录的 keys.properties：')
  console.log('')
  console.log(`    APP_KEY=${key}`)
  console.log('')
}

console.log('========================================')
console.log('后端就绪！')
console.log('')
console.log('接下来：')
console.log('  1) 项目根目录建 keys.properties（可以从 keys.properties.example 拷），填两行：')
console.log(`         APP_KEY=<上面那个密钥>`)
console.log(`         BASE_URL=${url}`)
console.log('  2) Android Studio 打开项目根目录，Build APK，装到两台手机上')
console.log('')
console.log(`自检：浏览器打开 ${url}/api/health 应该看到 {"ok":true,...}`)
console.log(`管理后台：${url}/admin （第一次打开会让你输入密钥，存在浏览器本地）`)
console.log('========================================')
