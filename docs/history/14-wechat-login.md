# 微信登录流程与排障

更新：2026-10-18。用户确认没有自有域名，当前使用云托管调用。此前尝试将默认云托管域名加入 request 合法域名，被微信公众平台明确拒绝，HTTPS 默认域名方案已撤回。

## 官方流程与代码

依据 [微信官方小程序登录文档](https://developers.weixin.qq.com/miniprogram/dev/framework/open-ability/login.html)：

1. 用户点击登录，`pages/login/index.js` 调用 `wx.login` 获取临时 code。阻止重复点击；失败后再次点击必须获取新 code，不保存或自动重试旧 code。
2. `lib/api.js` 的 `wechatLogin` 向开发者后端发送 `POST /api/v1/auth/wechat-login`，请求体仅含 code，不带旧 Bearer token 或业务幂等键。登录单独使用 60 秒超时以覆盖网关最多 55 秒的冷启动等待；普通业务请求仍为 8 秒。
3. 后端 `WxProvider.login` 使用服务端 AppID / AppSecret 请求微信 `auth.code2Session`，校验返回的 OpenID。AppSecret 不进入小程序；不下发 session_key，不记录 code 或微信原始响应，不自动重放一次性 code。
4. `AuthService.wechatLogin` 根据 AppID + OpenID 确认身份，签发有效期 8 小时的业务令牌，返回 accessToken、expiresAt、role、bindingStatus 和 person。服务端仅保存令牌散列，OpenID 加密保存。
5. `app.saveLogin` 校验返回会话有效后保存业务 token；随后 `/me` 获取当前身份，ACTIVE 用户进入任务页，其余进入邀请码绑定/待审核流程。后续业务请求带 Bearer token，后端核验有效期和权限；401 清除当前会话并回到登录页。

微信 session_key 与本系统业务 token 是不同会话；本系统登录是否有效由后端业务 token 校验决定，不能用 `wx.checkSession` 代替后端鉴权。`callContainer` 和 `wx.request` 是请求通道，云环境关联不是 `code2Session` 登录协议的步骤。

## 当前采用云托管调用

```sh
npm --prefix miniprogram run build:production -- --transport cloud \
  --env yongsen-training-d0eldief700a7cd --service yongsen-api \
  --public-https https://yongsen-api-330499-11-1304903095.sh.run.tcloudbase.com
```

当前开发者工具中的 `miniprogram/release/production-ZzLm8Y` 已切回以上 cloud 配置并重新编译，`urlCheck: true` 保持开启。业务 API 通过 `wx.cloud.callContainer` 调用，不把默认域名写入 request 合法域名列表。该目录名保留以避免反复切换工程；源码仍使用本机开发配置。

[CloudBase 官方说明](https://docs.cloudbase.net/run/develop/access/mini)要求小程序与目标环境关联；如使用共享环境，则必须由资源方开启共享并使用真实的 resourceAppid/resourceEnv。不能凭空填写资源 AppID。

按[官方账号关联说明](https://cloud.tencent.com/document/product/876/57380)，已有腾讯云环境应先核验腾讯云账号中心的“微信公众平台”关联，再在微信开发者工具的云开发环境管理中选择已有环境。目标小程序为 `wx505c1dc50f1f7099`，环境为 `yongsen-training-d0eldief700a7cd`，服务为 `yongsen-api`。

微信公众平台已明确提示云托管默认域名仅用于测试，不能配置为正式 request 合法域名。构建脚本现在拒绝 HTTPS 模式下的 `*.tcloudbase.com`、`*.tcloudbaseapp.com`、`*.wxcloudrun.com` 默认域名；如将来采用普通 HTTPS，必须先准备符合微信要求的自有备案域名及证书。云调用不需要这项 API 域名配置，但 COS 媒体域名和消息回调仍须分别验收。

## 验证边界

- 小程序单元测试 41 项通过，覆盖一次性 code、重复点击、匿名交换、会话返回校验、401、域名错误和既有学习事件逻辑。
- 后端 `AuthServiceWechatTest`、`WxProviderTransportTest` 共 23 项通过，覆盖官方接口参数、失败不重试、身份与令牌处理、敏感字段不下发。这些使用测试替身，不代表真实微信登录成功。
- 云端 HTTPS `/health` 返回 200；开发者工具在域名校验开启时对同一域名的 `wx.request` 返回 `url not in domain list`。19:09 实际点击新工程登录按钮，页面显示“登录服务域名尚未配置，请管理员在微信公众平台添加 request 合法域名”，未形成登录态。客户端已将其区分为 `REQUEST_DOMAIN_NOT_CONFIGURED`，不再误报普通网络问题。
- 原 cloud 版本点击登录仍返回 `INVALID_HOST`，尚未到达身份交换后端。当前应完成目标环境关联及云调用验证，再验收真实 code 交换、`/me`、员工绑定和手机学习闭环。

- 本轮云管理 API 读回：环境状态 NORMAL；云托管服务的公网与微信入口路由均 Enable=true、Status=SUCCESS。该结果不能证明小程序已获得环境访问权。

### 环境关联复核

用户已确认腾讯云账号中心显示永森油服小程序。账号关联不等同于环境导入；按 [创建环境官方步骤](https://docs.cloudbase.net/quick-start/create-env)，还需在微信开发者工具的云开发控制台中，依次打开“设置 → 环境名称 → 管理我的环境 → 使用已有腾讯云环境”，选择现有环境并扫码完成转换。

19:26 通过当前小程序发起无业务写入的环境诊断：调用一个不存在的诊断函数，返回 `INVALID_ENV / Environment not found`，而不是函数不存在；同一环境在云管理 API 中状态 NORMAL。这是小程序尚不能访问环境的证据，不能通过更改业务登录接口或添加默认域名来修复。确认环境关联后需重新编译并实际点击登录，成功返回业务 token 和 `/me` 后才能判定登录通过。
