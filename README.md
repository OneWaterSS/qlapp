# qlapp · 情侣小站

一个**只属于两个人的 App**：相册、任务、游戏、日记、留言，五合一，配上自己部署的云端后端。

- 📱 Android 客户端（Kotlin + Jetpack Compose）
- ☁️ 后端跑在 Cloudflare Workers（免费额度足够两个人用）
- 🔒 没有登录、没有注册、没有配对码 —— 打开就能用
- 🚫 数据全在你自己的 Cloudflare 账号里，不经过任何第三方服务器

> 默认示例昵称是「小江」和「甜甜」。想换成你们俩的名字？见 [改成你们的名字](#改成你们的名字)。

---

## 目录

- [它长什么样](#它长什么样)
- [功能](#功能)
- [技术栈](#技术栈)
- [架构](#架构)
- [目录结构](#目录结构)
- [从零部署（完整教程）](#从零部署完整教程)
  - [第 0 步：准备](#第-0-步准备)
  - [第 1 步：部署后端](#第-1-步部署后端)
  - [第 2 步：配置 App](#第-2-步配置-app)
  - [第 3 步：打包 APK](#第-3-步打包-apk)
  - [第 4 步：装到手机上](#第-4-步装到手机上)
  - [第 5 步：网页管理后台](#第-5-步网页管理后台)
- [改成你们的名字](#改成你们的名字)
- [改纪念日](#改纪念日)
- [常见问题](#常见问题)
- [安全说明](#安全说明)
- [License](#license)

---

## 它长什么样

底部 5 个标签页，全部两字：**相册 / 任务 / 游戏 / 日记 / 留言**。

| 页面 | 长什么样 |
| --- | --- |
| 相册 | 网格照片墙，多选上传，点开大图，长按可下载原图 |
| 任务 | 待办清单，谁创建、谁完成都记名 |
| 游戏 | 三款街机小游戏（飞机 / 钢琴 / 跑酷）+ 联机五子棋 |
| 日记 | 月历，格子里是两个人当天的心情 Emoji，点某天看详情 |
| 留言 | 便签墙，两列瀑布流，自己的黄色、对方的蓝色 |

第一次打开会先让你选身份（我是小江 / 我是甜甜），选完就记住，之后不再问。

---

## 功能

**相册**
- 多选批量上传，上传前自动把长边压到 1600px，省流量
- 照片原图存在 Cloudflare KV 里，不占数据库
- 下载原图到相册：Android 10+ 直接存 `Pictures/小江&甜甜`，不用给存储权限

**任务**
- 新建 / 编辑 / 删除，标记完成或撤销
- 每条都记「谁创建」「谁完成」

**游戏**
- 街机：飞机大战、钢琴块、跑酷，分数上传到云端排行榜，两人比拼最高分
- 五子棋：15×15 无禁手、黑先，联机对弈（前台每 2 秒轮询同步）
- 五子棋是水墨风界面：宣纸底、墨色棋盘、一枚朱红点睛
- 入口卡片直接显示两人战绩，领先的一方标红

**日记**
- 月历视图，格子显示两个人当天的心情
- 同一人同一天一篇，可以改、可以删
- 「是不是我写的」按**名字**判断，不是按手机

**留言**
- 便签墙瀑布流，自己黄、对方蓝
- 墙上的便签不能改也不能删 —— 唯一的删除出口在网页管理后台

**网页管理后台**（`你的后端地址/admin`）
- 改两个人的昵称、改纪念日
- 删任何一条留言 / 日记（App 里删不掉的，这里能删）
- 上传 / 删除照片，管任务，改游戏纪录

---

## 技术栈

| 层 | 用了什么 |
| --- | --- |
| 客户端 | Kotlin、Jetpack Compose（Material 3）、OkHttp、org.json、Coil 2.7 |
| 最低版本 | minSdk 26（Android 8.0），targetSdk 36 |
| 后端 | Cloudflare Workers + [Hono](https://hono.dev/) |
| 数据库 | Cloudflare D1（SQLite），存结构化数据 |
| 文件存储 | Cloudflare KV，存照片原图 |
| 部署 | Wrangler CLI |
| 管理后台 | 纯静态 HTML，由 Worker 直接返回 |

> 为什么不用 R2 存照片？R2 要绑卡激活，KV 开箱即用，两个人这点照片量完全够。

---

## 架构

```
┌────────────────────┐          ┌──────────────────────────────────┐
│   Android App      │          │      Cloudflare Workers          │
│  (Kotlin/Compose)  │          │  (Hono 路由 + 管理后台 HTML)      │
│                    │          │                                  │
│  相册 / 任务 / 游戏 │  HTTPS   │   /api/tasks                     │
│  日记 / 留言        │ ───────► │   /api/photos                    │
│                    │  X-App-Key│  /api/diary                     │
│  身份：X-Identity  │  X-Identity│ /api/messages                  │
└────────────────────┘          │   /api/game-records              │
                                │   /api/gomoku/*                  │
        两台手机                 │   /admin  (管理网页)              │
        共用同一份                └───────────┬──────────┬───────────┘
        云端数据                             │          │
                                    ┌────────▼──┐  ┌────▼─────┐
                                    │    D1     │  │    KV    │
                                    │  结构化数据 │  │  照片原图 │
                                    └───────────┘  └──────────┘
```

**鉴权很简单**：后端和 App 共享一个密钥 `APP_KEY`，App 每个请求带 `X-App-Key` 头（图片 URL 因为要能被 `<img>` 直接加载，用 `?key=` 查询参数）。密钥不对就是 401。

**「谁做的」怎么判**：请求头 `X-Identity` 带的是本机昵称（小江 / 甜甜），后端算 `mine = (作者 === 身份名)`。不用设备 ID 判归属 —— 同一台手机换过身份之后，两个人的内容会落到同一个设备 ID 上，拿它判必然串。

---

## 目录结构

```
qlapp/
├── app/                          # Android 客户端
│   └── src/main/java/com/example/qlapp/
│       ├── data/                 # 网络层、本地存储、数据模型
│       │   ├── Api.kt            # 所有后端请求
│       │   ├── Prefs.kt          # SharedPreferences（昵称、身份、纪念日）
│       │   ├── IdentityHeader.kt # X-Identity 头编码（中文必须百分号编码）
│       │   └── Models.kt
│       ├── games/                # 游戏逻辑（纯 Kotlin，可单测）
│       │   ├── Gomoku.kt         # 五子棋规则
│       │   ├── SongLibrary.kt    # 钢琴曲谱
│       │   └── ...
│       ├── ui/                   # Compose 界面
│       │   ├── AppRoot.kt        # 底部 5 个标签
│       │   ├── IdentityGate.kt   # 第一次进来的身份选择 ★ 改名字在这里
│       │   ├── AlbumScreen.kt / TaskScreen.kt / GameScreen.kt
│       │   ├── DiaryScreen.kt / WallScreen.kt
│       │   └── ...
│       └── util/                 # 图片压缩、批量下载
│
├── backend/                      # Cloudflare Workers 后端
│   ├── src/index.ts              # 全部路由 + 管理后台返回
│   ├── public/admin.html         # 网页管理后台
│   ├── schema.sql                # 建表语句
│   ├── migrations/               # 增量迁移
│   ├── setup.mjs                 # ★ 一键部署脚本
│   ├── wrangler.toml.example     # 配置模板（真实的 wrangler.toml 不进版本库）
│   └── test/                     # 后端路由测试（Miniflare）
│
├── keys.properties.example       # ★ App 配置模板（真实的 keys.properties 不进版本库）
├── .gitignore                    # 已排除所有密钥/域名/账号/构建产物
└── README.md
```

---

## 从零部署（完整教程）

整个过程大约 20 分钟，**完全免费**（Cloudflare 免费额度：Workers 每天 10 万次请求，D1 5GB，KV 1GB）。

### 第 0 步：准备

你需要：

1. **一个 Cloudflare 账号** —— [注册](https://dash.cloudflare.com/sign-up)（免费，不绑卡也能用 Workers / D1 / KV）
2. **Node.js 18+** —— [下载](https://nodejs.org/)
3. **Android Studio** —— [下载](https://developer.android.com/studio)（想自己打包 APK 才需要）

### 第 1 步：部署后端

```bash
cd backend
npm install

# 登录 Cloudflare（会弹浏览器授权）
npx wrangler login

# 一键部署：建数据库 → 建 KV → 建表 → 部署 → 设置密钥
node setup.mjs
```

脚本会依次做 7 件事，**重复运行是安全的**：

1. 检查 Cloudflare 登录状态
2. 准备 `wrangler.toml`（不存在就从 `.example` 拷一份）
3. 创建 D1 数据库 `qlapp-db`，把 `database_id` 写进配置
4. 创建 KV 命名空间 `PHOTOS`，把 `id` 写进配置
5. 建表
6. 部署 Worker，打印出 `https://qlapp-backend.xxx.workers.dev`
7. 生成一个随机密钥，存成 Cloudflare secret（**同时打印出来，下面要用**）

跑完你会看到两样东西，抄下来：

```
部署地址：https://qlapp-backend.xxxxxxxx.workers.dev
共享密钥：APP_KEY=qlapp-xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx
```

> **关于密钥**：它不写在源码里，而是存在 Cloudflare 的 secret 里。仓库是公开的，源码里出现密钥等于泄密。
> 如果你想指定密钥（比如从旧部署迁移），先设环境变量：`APP_KEY=我的密钥 node setup.mjs`

自检一下：浏览器打开 `https://你的地址/api/health`，看到 `{"ok":true,...}` 就成功了。

### 第 2 步：配置 App

回到项目根目录，把配置模板复制一份：

```bash
cp keys.properties.example keys.properties
```

打开 `keys.properties` 填三个值：

```properties
# 第 1 步打印出来的部署地址
BASE_URL=https://qlapp-backend.xxxxxxxx.workers.dev

# 第 1 步打印出来的密钥（必须和后端 secret 完全一致）
APP_KEY=qlapp-xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx

# 「在一起的那天」，毫秒时间戳。可以先用默认值，之后在管理后台改
ANNIVERSARY=1735689600000
```

> `keys.properties` 已经在 `.gitignore` 里，**不会被提交**，只存在你自己机器上。
> 这三个值会在编译时被写进 App（Gradle 的 `buildConfigField`），改完要**重新 Build**。

### 第 3 步：打包 APK

**方式 A：命令行**

```bash
# Windows
.\gradlew.bat testDebugUnitTest assembleDebug

# macOS / Linux
./gradlew testDebugUnitTest assembleDebug
```

产物在 `app/build/outputs/apk/debug/app-debug.apk`。

**方式 B：Android Studio**

用 Android Studio 打开项目根目录 → 菜单 `Build` → `Build App Bundle(s) / APK(s)` → `Build APK(s)`。

> ⚠️ **打包前检查 ABI**：如果之前在 Android Studio 里对模拟器（x86_64）跑过，打包可能会被注入
> `android.injected.build.abi=x86_64`，导致 APK 里没有 arm 的原生库，真机安装报
> `INSTALL_FAILED_NO_MATCHING_ABIS (-113)`。
> 本项目已在 `app/build.gradle.kts` 里显式钉死 `abiFilters`，一般不会再出问题。
> 万一遇到，在项目根目录跑一次 `.\gradlew.bat --stop` 再重新打包。

### 第 4 步：装到手机上

把 `app-debug.apk` 拷到两台手机上安装（微信/QQ 传、数据线拷都行，安装时允许「未知来源」）。

打开 App：

1. 第一次会让你选身份 —— **两个人要选不同的**（一个选小江，一个选甜甜）
2. 之后就能用了。两台手机看到的是同一份数据，谁发的会记在谁名下

> 这是 debug 签名的 APK，两台手机能互相覆盖安装（同一个签名）。
> 想长期用、想以后能升级，建议自己生成一个正式签名（`keytool` + `signingConfigs`），别用 debug 签名。

### 第 5 步：网页管理后台

浏览器打开：

```
https://你的后端地址/admin
```

第一次打开会让你**粘贴密钥**（就是 `keys.properties` 里那个 `APP_KEY`），存在浏览器本地，之后不用再输。

管理后台能做的：改昵称、改纪念日、删任何留言/日记、上传照片、管任务、改游戏纪录。

> 换密钥或者换浏览器：点右上角「换密钥」重新输入。

---

## 改成你们的名字

默认昵称是「小江」和「甜甜」，改起来很简单 —— 只有一处：

**`app/src/main/java/com/example/qlapp/ui/IdentityGate.kt`** 第 27 行：

```kotlin
/** 仅有的两个身份，顺序就是界面上的先后。 */
val CoupleIdentities = listOf("小江", "甜甜")
```

改成：

```kotlin
val CoupleIdentities = listOf("阿猫", "阿狗")
```

然后改完重新 Build 就行。

顺便，这几处也建议一起改（都是展示用的文字，不影响功能）：

| 文件 | 改什么 |
| --- | --- |
| `app/src/main/res/values/strings.xml` | App 名字 |
| `backend/public/admin.html` | `<title>` 和 `<h1>`（管理后台标题） |
| `app/src/main/res/drawable-nodpi/qlapp_icon_image.png` | App 图标（换成你俩的图） |

> ⚠️ 名字里**不要有 emoji 或生僻符号**。这个名字会放进 HTTP 请求头 `X-Identity`，
> 代码里做了百分号编码处理，但极端字符仍然可能出问题。中文汉字、英文字母、数字都稳。

---

## 改纪念日

两种方式：

1. **网页管理后台**（推荐）—— 打开 `/admin` → 设置 → 改「纪念日」→ 保存。改的是云端数据，两台手机都会更新。
2. **改 `keys.properties` 的 `ANNIVERSARY`** —— 这是**本地兜底值**，只在手机上没存过的时候生效。改完要重新 Build。

时间戳怎么算？在浏览器控制台跑 `new Date('2026-01-01').getTime()`，或者用在线工具把日期转成毫秒。

---

## 常见问题

<details>
<summary><b>Q：打开 App 提示「密钥不对」/ 什么都加载不出来</b></summary>

`keys.properties` 里的 `APP_KEY` 和后端 Cloudflare secret 里的不一致。检查：

```bash
cd backend
npx wrangler secret list        # 看看有没有 APP_KEY
```

如果密钥丢了，删掉重新设一个，然后同步改 `keys.properties`：

```bash
npx wrangler secret delete APP_KEY
npx wrangler secret put APP_KEY     # 粘贴新密钥
```

⚠️ 换密钥后**所有已装的 App 都要重新填新密钥**（也就是要重新打包安装），否则连不上。
</details>

<details>
<summary><b>Q：workers.dev 地址在国内很慢 / 连不上</b></summary>

`*.workers.dev` 在国内会被限流。解决办法是绑自己的域名：

1. 把域名加到 Cloudflare（Add a site），去注册商把 NS 改成 Cloudflare 给的
2. NS 生效后，在 `backend` 目录跑：

```bash
node add-domain.mjs api.你的域名.com
```

脚本会把路由写进 `wrangler.toml` 并重新部署，Cloudflare 自动签证书。

3. 把 `keys.properties` 的 `BASE_URL` 改成 `https://api.你的域名.com`，重新打包
</details>

<details>
<summary><b>Q：日记/留言显示「不是我写的」或者串了</b></summary>

「是不是我写的」是按**身份名**判断的，不是按手机。所以：

- 两台手机必须选**不同的**身份（一个选小江，一个选甜甜）
- 如果一台手机中途换过身份，之前发的会留在旧名字下 —— 这是正常的，不是 bug
- 想删掉串掉的条目：用网页管理后台删（App 里删不掉留言）
</details>

<details>
<summary><b>Q：照片上传失败</b></summary>

- 单张照片大小限制在 KV 单值 25MB 以内（压缩后一般 1~3MB，没问题）
- 检查 KV 命名空间有没有建成功：`npx wrangler kv namespace list`
- 检查 `wrangler.toml` 里 KV 的 `id` 是不是还是 `KV_ID_PLACEHOLDER`（说明第 4 步没跑成功，重跑 `node setup.mjs`）
</details>

<details>
<summary><b>Q：五子棋一直「等待对手」</b></summary>

五子棋是**轮询**同步（前台每 2 秒拉一次），不是 WebSocket。所以：

- 两边都要**把 App 开在前台**，切后台就不刷新了
- 两边必须用同一个后端地址、同一个密钥
- 后端地址不通（国内访问 workers.dev 慢）时也会一直等
</details>

<details>
<summary><b>Q：怎么重置全部数据？</b></summary>

删掉表重建：

```bash
cd backend
npx wrangler d1 execute qlapp-db --remote --command "DROP TABLE IF EXISTS messages"
npx wrangler d1 execute qlapp-db --remote --file=./schema.sql
```

照片在 KV 里，要单独清：`npx wrangler kv key list --binding=PHOTOS` 然后逐个删。
（谨慎操作，删了不可恢复。）
</details>

<details>
<summary><b>Q：能加第三个人吗？</b></summary>

设计上就是两个人的（身份选项写死两个，五子棋战绩卡片也按两个人排版）。
要多人用的话得改 `CoupleIdentities` 和相关排版逻辑，工作量不小。
</details>

---

## 安全说明

- **密钥不进源码**：`APP_KEY` 存在 Cloudflare secret 里；App 侧从 `keys.properties` 读（`.gitignore` 已排除）
- **配置不进源码**：`wrangler.toml`（含数据库 ID、KV ID、域名）和 `keys.properties`（含地址、密钥）都不提交，仓库里只有 `.example` 模板
- **照片 URL 用 `?key=` 而不是请求头**：因为 Coil 加载图片时带不了自定义头。这会让密钥出现在 URL 里（浏览器历史、日志），但两个人在自己服务器上用，风险可接受
- **管理后台的密钥存在浏览器 localStorage**，不上传。第一次打开需要手动粘贴一次
- **没有用户系统**：拿到密钥就是完全管理员。所以密钥别外传、别发群里
- 建议自己生成**正式签名**打包，别用 debug 签名长期使用

---

## License

[MIT](LICENSE) —— 随便用，改了也不用告诉我。祝你们幸福 ❤️
