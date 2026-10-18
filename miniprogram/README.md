# 永森油服原生微信小程序

本目录包含五个真实原生页面：登录及邀请码绑定、任务列表、任务详情及订阅/本人确认、视频/PDF 学习、个人记录。没有把浏览器学员预览当作小程序。

## 本地运行

1. 启动仓库的 Java 后端（默认 `http://127.0.0.1:8080`）；管理员在 Web 后台预建人员和个人邀请码，核对绑定后发布任务。
2. 微信开发者工具导入本目录。项目已配置指定的 AppID `wx505c1dc50f1f7099`，项目名为 `yongsen-training-wechat`；AppID 是公开标识，项目不包含 AppSecret。实际开发者权限、云环境关联和真实账号登录仍需在微信官方工具中验证。
3. 本机开发在该项目的本地设置关闭合法域名校验；`project.private.config.json` 已加入本目录忽略列表。正式环境必须保持域名校验并使用配置过的 HTTPS 域名。
4. `lib/config.js` 保留 `transport: 'request'` 和本机 API 默认地址。模拟只允许 request 通道、`http://localhost|127.0.0.1:<端口>/api/v1`、开发者工具和 `develop` 环境同时满足时，由登录页开关明确启用；默认关闭。`mock:alice` 等身份还需要后端 local/test 模式。云托管、手机、release、远端服务均不能使用该入口。
5. 模拟微信订阅会显式显示“本地模拟”。真实模式由用户点击按钮直接调用 `wx.requestSubscribeMessage`；登录用 `wx.login`，仅服务端交换身份；小程序没有 AppSecret 或管理员 token。

任务卡片页面为 `pages/task/index?assignmentId=...&notificationId=...`。如果通知入口登录过期，重新登录后恢复原任务入口；后端再次检查本人任务权限，卡片 ID 不是访问凭据。

## 微信云开发接入（当前部署方案）

当前使用 CloudBase 云托管网关 + SCF Java 后端 + 云 MySQL + 私有 COS。环境 `yongsen-training-d0eldief700a7cd`，服务名 `yongsen-api`，管理后台 origin 为 `https://yongsen-api-330499-11-1304903095.sh.run.tcloudbase.com`。API 使用 `wx.cloud.callContainer`，媒体直接读取 COS 签名地址；服务端部署状态见 [云部署执行记录](../docs/12-cloud-deployment-status.md)。

`release/production-3XjvzA` 已生成、导入微信开发者工具并编译到登录页面，**尚未上传体验版**。当前腾讯云账号未关联该小程序，云调用实测 `404 / INVALID_HOST`。需账号本人扫码完成腾讯云账号的小程序关联，并在开发者工具关联已有云环境后继续验证，不能关闭校验或使用模拟身份代替。

复用现有 Java 17/Spring Boot 后端，Java 实际运行于 SCF，云托管只承载入口网关。客户端 `transport: 'cloud'` 时，在启动阶段执行 `wx.cloud.init({ env: cloudEnv })`，API 经 `wx.cloud.callContainer` 调用 `/api/v1/...`，设置 `X-WX-SERVICE: cloudService`。请求仍携带自有 Bearer 和幂等键，超时为 8 秒，学习采集的 10 秒无 ACK 暂停规则不变；云配置错误不会退回本机服务。

身份链路保持 `wx.login → code → /auth/wechat-login → 服务端 code2Session → 自有 accessToken`。登录 code 每次点击重新获取，不持久化或自动重放。AppSecret、session_key 和云平台密钥只能由后端保存；客户端不提交 openid 作为身份凭据，服务端也不能直接信任可从公网伪造的 `X-WX-OPENID`。本地登录缓存绑定 transport/API 地址/云环境/服务，切换环境须重新登录。当前会话返回 401 时立即暂停学习并回登录页；晚到的旧请求 401 不会清掉新会话。

`callContainer` 只承载 API。原生 `video`、`image` 组件须直接访问 HTTPS URL：媒体票据接口可返回绝对签名地址；若返回相对地址，则用 `apiBase` 去掉 `/api/v1` 后的 HTTPS origin 拼接。云托管需要保留受票据保护的 HTTPS 媒体入口或返回对象存储签名地址，并核对相关微信域名配置、视频 Range 和图片实际加载。不能把云 API 调用成功当作视频/PDF 已能播放。Web 管理后台的公网入口由服务端部署另行配置。

先在实际云托管中确定环境 ID、服务名和媒体 HTTPS 地址，再执行：

```sh
npm run build:production -- --env "$WECHAT_CLOUD_ENV" --service "$WECHAT_CLOUD_SERVICE" --public-https "$TRAINING_PUBLIC_HTTPS"
```

三个变量分别是实际环境 ID、服务名和公开 HTTPS origin（或以 `/api/v1` 结尾的地址），均不应包含秘密。不指定 `--transport` 时保留 cloud 模式，也可显式传 `--transport cloud`；HTTPS 模式禁止混入云托管环境参数。生成的独立工程位于已忽略的 `release/production-*`，保留真实 AppID、强制合法域名检查，不复制 `project.private.config.json`、本地测试、node_modules 或验收材料，也不覆盖原来的 `lib/config.js`。将输出目录作为生产项目导入微信开发者工具。生成工程不代表上传、部署或真实微信验证完成。

当前生产工程已填入上述云环境与服务名，后端已保存正式“新任务发布通知”模板（`thing1 → title`、`time20 → dueAt`）；任务必须有截止时间。公众平台回调和真实订阅尚待验证。通知验收要求真实用户手机收到卡片并点击正确个人任务，同时核验回调结果或 UNKNOWN 分支，不能把模板已配置等同于已送达。

保留 `--transport https --public-https "$TRAINING_PUBLIC_HTTPS"` 作为备用构建方式；仅在已有 HTTPS 域名及微信 request 合法域名配置时使用，当前生产工程采用 cloud transport。

实现依据：[云托管小程序调用](https://docs.cloudbase.net/run/develop/access/mini)、[Java 容器部署](https://docs.cloudbase.net/en/run/quick-start/dockerize-java)、[微信官方 wx.login 类型与 code 说明](https://github.com/wechat-miniprogram/api-typings/blob/master/types/wx/lib.wx.api.d.ts)、[微信官方 callContainer 接口定义](https://github.com/wechat-miniprogram/api-typings/blob/master/types/wx/lib.wx.cloud.d.ts)。实际环境必须关联当前小程序；现有 AppID 配置不等于环境已关联。

## 采集与核验

`lib/session-tracker.js` 是不依赖页面的采集器：1 秒采样、5 秒上传、10 秒无 ACK 暂停、同批次同序号重试、单请求在途。只显示服务端确认进度。`learn` 页面读取真实视频事件，限定 1 倍速，暂停/隐藏/缓冲不自动推进；PDF 为后台生成的页图，用真实 `scroll-view` 与图片几何换算原图可见坐标。完整覆盖、有效停留、并发和会话有效性由后端计算。

任务材料及学习规则由发布时固定。用户离开、弱网、强杀导致未核验的部分需要补学。系统不把播放/页面展示等同于本人持续专注、理解或行业考核合格。

## 测试

```sh
npm install
npm test
npm run test:native
```

单元测试覆盖采样基线、ACK 超时、不可变重传、并发请求、后台暂停、时钟倒退、PDF 坐标、mock 隔离、云请求协议、登录失效竞态、跨环境缓存和生产构建拒绝规则。云 SDK 使用协议测试桩，不能代表实际云环境已联通。`scripts/native-e2e.js` 通过官方 `miniprogram-automator` 打开真实五页，不伪造视频事件或完成进度。它向本机后端创建独立验收人员/任务，在真实组件中播放 12 秒 MP4、滚动读取 PDF 至少 30 秒、点击本人确认，再检查个人记录。需要本机 `ffmpeg`、后端可用及微信开发者工具服务端口开启。脚本不会自动开关工具安全设置、提取登录票据或修改其他项目。

开发者工具端口关闭或工具要求扫码时，按官方 UI 完成前置设置；本地自动化不替代真实微信手机验收。脚本结果和截图存放于忽略的 `artifacts/`。`NATIVE_WS` 可指定已打开本项目的自动化连接，默认 `ws://127.0.0.1:9420`。

正式接入还必须验证实际主体/模板资格、HTTPS request/图片/媒体域名、真实微信登录与授权、手机收到指定卡片并点击正确任务、回调精确关联和无法关联的 UNKNOWN 路径，以及 iOS/Android 来电/锁屏/弱网。任何本地 mock 送达不能写成真实微信送达。
