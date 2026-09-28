#!/usr/bin/env node
/**
 * 给 Worker 绑定自定义域名（解决 workers.dev 在国内被限流）
 *
 * 用法：node add-domain.mjs api.你的域名.com
 *
 * 前置条件：
 *   1. 域名已经加到 Cloudflare（Add a site），并且 NS 已生效
 *   2. 已完成 wrangler login
 *
 * 做两件事：
 *   1. 把 routes 写进 wrangler.toml
 *   2. 执行 wrangler deploy（Cloudflare 会自动签发证书）
 */
import { execSync } from 'node:child_process'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const ROOT = path.dirname(fileURLToPath(import.meta.url))
const TOML = path.join(ROOT, 'wrangler.toml')
const domain = (process.argv[2] || '').trim()

if (!domain || !domain.includes('.') || domain.startsWith('http')) {
  console.log('用法: node add-domain.mjs <域名>')
  console.log('例子: node add-domain.mjs api.xiaowo.top')
  process.exit(1)
}

let toml = fs.readFileSync(TOML, 'utf8')
// 先清掉旧的 routes
toml = toml.replace(/^#?\s*routes\s*=\s*\[[\s\S]*?\]/m, '').trimEnd()
// routes 必须放在顶层：TOML 里 [[table]] 之后的键值都属于那个表
const block =
  `\n# 自定义域名：workers.dev 在国内会被限流，用自己的域名才能直连\n` +
  `routes = [\n  { pattern = "${domain}", custom_domain = true }\n]\n`
const firstTable = toml.search(/^\[\[/m)
toml = firstTable >= 0
  ? toml.slice(0, firstTable).trimEnd() + '\n' + block + '\n' + toml.slice(firstTable)
  : toml + '\n' + block
fs.writeFileSync(TOML, toml, 'utf8')
console.log(`已把 ${domain} 写进 wrangler.toml`)

try {
  execSync('npx wrangler deploy', { cwd: ROOT, stdio: 'inherit' })
} catch (e) {
  console.log('\n部署失败。常见原因：域名还没加到 Cloudflare，或者 NS 还没生效。')
  process.exit(1)
}

console.log('\n========================================')
console.log(`绑定完成：https://${domain}/api/health`)
console.log('')
console.log('还有一步要在 Cloudflare 网页做（脚本没权限改 DNS）：')
console.log(`  1) 打开 Cloudflare → 你的域名 → DNS → Records`)
console.log(`  2) 找到 ${domain} 这条记录，点 Edit`)
console.log(`     类型改成 CNAME，目标填优选域名，例如 cf.090227.xyz`)
console.log(`     代理状态一定要选「仅 DNS」（灰云）`)
console.log('  3) 保存，等几分钟生效')
console.log('')
console.log('后端地址写死在 App 代码里（Prefs.kt 的 DEFAULT_BASE_URL），App 内没有改地址的入口。')
console.log(`把它改成下面这个地址，然后重新打包 APK：`)
console.log(`  https://${domain}`)
console.log('========================================')
