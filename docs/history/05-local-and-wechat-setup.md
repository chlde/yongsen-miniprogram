# 本地运行与永森油服账号接入

本工程从零开发。`miniprogram/` 是正式学员入口的原生微信小程序；`web/` 是管理员后台及便于本地验收的学员浏览器预览；`backend/` 是 Java 服务。本地数据和文件均落盘，不依赖微信云开发。

## 1 本地启动

依赖 Java 17、Maven 3.6.3 或更新版本、Node.js 20.x（至少 20.19）或 22.12 及以上版本、npm、ffprobe、pdfinfo、pdftoppm。生成验收样本另需系统 ffmpeg；浏览器验收使用已安装的 Google Chrome，并需要 Playwright 自带的 FFmpeg。脚本优先使用本机 Homebrew Java 17；其他机器可设置 `TRAINING_JAVA_HOME` 为 Java 17 安装目录。这些外部工具应可从命令行访问。

在项目根目录执行：

```sh
npm ci
npx playwright install ffmpeg
npm run setup
npm run dev
```

管理后台与浏览器学员预览：`http://127.0.0.1:5173`。API：`http://127.0.0.1:8080/api/v1`。本地管理员账号 `admin`，初始密码 `LocalTrain123!`；这组默认值只允许本地环境。浏览器学员标识相同会进入同一个模拟微信身份；仅网页与 API 都为回环地址时才显示该入口，正式 Web 只提供管理后台。先由管理员创建人员和邀请码，学员申请后由管理员核对，才能领取个人任务。

数据目录默认 `.local/dev/`，保留数据库、原材料及 PDF 页图，重启后继续使用。不要把已有业务数据目录作为测试目录。`npm run dev` 固定使用 local profile 和模拟微信，并运行已构建的 jar 快照，修改后端代码后需要停止、执行 `npm run setup` 重新构建再启动；前端由 Vite 自动更新。按 Ctrl+C 停止服务；默认 8080、5173 端口被占用时应先停止原服务，启动脚本不会接管已运行的进程。

本地微信渠道明确标记“本地模拟”。管理员的模拟收件箱可以查看对应任务卡片、触发送达回调、打开任务入口；这些记录不能用作真实微信送达证据。

微信开发者工具导入 `miniprogram/`，本机操作和自动化测试见该目录 README。占位 AppID `touristappid` 仅用于本地开发。真实 AppID 属于现有“永森油服”账号，不能另建一个账号替代。

## 2 本地验证

```sh
npm run verify
```

验证脚本构建并测试后端、Web 和原生采集器，在独立目录启动服务，以真实时间执行 API 和 Chrome 界面的完整流程，最后停机备份、从副本恢复并核对已完成记录。API 使用 18080、Web 使用 15173；不修改日常开发数据。每次的 `verification.json`、日志和证据保存在 `.local/verification/<时间>/`，浏览器报告在其 `browser-report/`、截图和失败诊断在其 `browser-results/`。单独执行 `npm run test:e2e` 时才默认使用根目录的 `playwright-report/` 和 `test-results/`。

原生端到端测试通过官方开发者工具自动化接口操作真实页面和组件，单独执行：

```sh
cd miniprogram
npm ci
npm run test:native
```

需要本机后端 8080 可用、该项目的自动化连接可用，以及开发者工具服务端口开启。测试结束后恢复此前设置。原生自动化结果在 `miniprogram/artifacts/`。开发者工具测试与手机上的真实微信送达、锁屏、来电测试分别记录。

已安装 MySQL 8、且 `mysql` 和 `mysqld` 在 PATH 中的机器可另执行 `npm run verify:mysql`：脚本使用已经构建的后端 jar，创建全新的私有 datadir，在回环地址 13368 端口启动隔离数据库，执行正式 DDL，再通过 18082 端口运行同一套 API 验收。它不连接或重置机器上已有的数据库，完成后关闭测试实例，证据保留在 `.local/mysql-check/`。

容量验证脚本 `scripts/load-local.mjs` 默认 100 个模拟用户、真实 30 分钟。先在独立数据目录、18081 端口启动本地后端，再执行 `node scripts/load-local.mjs`。它拒绝远程地址及真实微信渠道；不会向微信用户发消息。其结果只说明本机 API 和数据库负载，不代表生产媒体带宽。

## 3 真实微信配置

账号管理员需从“永森油服”后台提供公开 AppID、可用的订阅消息模板 ID 和模板字段定义。AppSecret、消息推送 Token、43 位 EncodingAESKey 通过本地私密环境配置，避免发在聊天、写入小程序或提交仓库。

环境变量清单见根目录 `.env.example`。该文件只是配置示例，运行脚本不会自动加载它。生产配置使用 `prod` profile、MySQL、独立数据库账号和随机管理员密码及加密密钥；加密密钥要与数据一起妥善备份，否则不能解密已绑定的 OpenID。

部署步骤：

1. 在独立数据库执行 `backend/schema-mysql.sql`，配置私密服务环境并直接启动构建好的 Java jar，显式设置 `SPRING_PROFILES_ACTIVE=prod`。不要使用固定为本地模拟的 `npm run dev` 部署生产。原材料目录仅由服务账号读取，不直接公开整个目录。
2. 配置 HTTPS 反向代理。`TRAINING_PUBLIC_BASE` 为外部 API 主机地址。上传支持最大 500 MiB 视频，代理大小限制和读取超时应相应设置；视频请求保留 Range 头与 206 响应。
3. Web 推荐与 API 同域 HTTPS 部署。执行 `VITE_API_BASE_URL=/api/v1 npm --prefix web run build` 后部署 `web/dist/`，反向代理将 `/api/v1`、媒体及回调路径转发到 Java 服务。若前后端分域，`VITE_API_BASE_URL` 使用完整 HTTPS API 基址，后端 `TRAINING_ALLOWED_ORIGINS` 填写精确 Web Origin（如 `https://admin.example.com`，多个用逗号分隔）。生产默认不放行跨域请求，不接受通配符或 HTTP Origin；`TRAINING_PUBLIC_BASE` 仍使用对外 API 地址。小程序 `lib/config.js` 配置同一 HTTPS API 基址，并在项目设置使用实际 AppID。AppSecret 始终仅在服务端。
4. 在微信后台配置 HTTPS request/download/image 等实际用到的合法域名，以及服务器出口 IP 白名单（若该账号接口要求）；以账号后台当前规则为准。
5. 开启加密消息推送，回调地址为 `https://<API域名>/integrations/wechat/yongsen/callback`，Token 和 AESKey 与服务环境一致。
6. 在管理员通知设置录入该账号已批准模板。当前实现支持映射任务标题 `title` 和截止时间 `dueAt`；模板必填项必须能由这两类数据完整提供。字段名按实际模板填写，例如 `thing1`、`time2`，示例不能直接当作账号已开通的模板。模板需要截止时间时，发布任务必须填写截止时间；任务未填写时服务端不会发送该字段，可能被微信拒绝。真机接入时须按实际模板验证，不以本地 mock 成功代替。
7. 体验版使用 `WECHAT_MINIPROGRAM_STATE=trial`，正式版改为 `formal`。由指定测试人员真实打开小程序、完成绑定、点击订阅授权后，发布测试任务。

现有账号后台读取曾被浏览器安全检查阻止，因此主体、类目、模板权限、AppID 和域名尚未由本工程核实。用户给出的后台会话链接不进入代码、日志或文档；这里不把它当作服务端接口凭据。

## 4 真实收件验收

至少保留一条同 AppID、同模板、指定个人微信的真实收件记录，并在 iOS/Android 验证点击卡片进入本人对应任务，无法查看他人任务。拒绝授权、无可用订阅、发送超时、失效绑定也需验证。首次发布及管理员催办都走同一通知状态机。

后台分别显示发送接口结果、匹配到的送达回执和用户打开时间。回调签名、加密和 AppID 均校验；只有可唯一关联的回执才确认送达。接口响应无可用消息 ID、回执缺失或关联不可靠时，状态保留未知。发送结果未知不会自动重发；再次提醒需要新授权及管理员对可能重复的明确确认。

真实手机收到卡片是 MVP 验收的一部分。本地模拟、加密协议测试、开发者工具通过均不能替代这一项，也不能据此宣称小程序已上线。

## 5 备份与恢复

本地 H2 必须在服务停止后复制整个数据目录；不要直接复制仍在写入的数据库。正式 MySQL 使用数据库一致性备份，同时备份私有材料、PDF 页图和密钥配置。恢复到隔离环境并禁用真实通知，核对任务人员/规则快照、文件 SHA-256、学习记录和确认时间后再使用。

保留故障发生前的日志和原数据。业务 API 没有“管理员直接改成完成”的入口；取消任务保留历史学习事实。文件哈希用于识别材料版本和检查一致性，不声称具有第三方时间戳或公证效力。
