# 联网游戏纪录

后端接口已经并入主部署流程，不需要单独操作：在 backend 目录跑 `node setup.mjs`
（或双击 setup.bat）即可，它会建表 + 部署 Worker，重复运行安全。
（`deploy-game-records.ps1` 是早期只加这一张表的一次性脚本；game_records 现已并入
schema.sql，正常用不到它了。）

接口使用现有 X-App-Key：
- GET /api/game-records 返回三款游戏已有的服务器纪录。
- POST /api/game-records 接收 game（air/piano/runner）、score（正整数）、owner（昵称）。
- 数据库原子比较分数；只接受更高分，平分保留先到达服务器的纪录。

客户端保存待上传分数和原作者；暂停、退出、结算时尝试上传。
进入游戏页、返回游戏页和前台停留每 30 秒重试并刷新，也提供手动刷新。
没有网络时保留待上传成绩及上次服务器结果，界面明确提示离线状态。
没有作者的历史本机纪录不会自动冒名上传。

验证：`npm test` 使用本地 Workers/D1 运行真实路由测试，覆盖鉴权、输入校验、
多玩家超分、平分、旧请求重试、三款游戏隔离及 20 个并发提交。
`node_modules\.bin\tsc.cmd --noEmit` 验证后端类型。
