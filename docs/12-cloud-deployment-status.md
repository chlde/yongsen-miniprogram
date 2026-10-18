# 云部署执行记录

更新：2026-10-18。**后台、Java API、云 MySQL 和异步材料处理已部署并完成云端验证；小程序环境关联结果尚未读回，真实登录和通知送达仍待验证，整体 MVP 尚未验收完成。** 整体模块与发布方式见 [13 架构和部署总览](13-project-architecture-and-deployment.md)。

## 已部署入口与资源

管理后台：[永森油服培训管理](https://yongsen-api-330499-11-1304903095.sh.run.tcloudbase.com/#admin)。默认 HTTPS 页面及健康接口已返回 200，管理员真实登录与业务 API 已验证。初始管理员凭据只保存在本机私密配置，不写入本文。

| 部分 | 实际部署 |
| --- | --- |
| CloudBase 环境 | `yongsen-training-d0eldief700a7cd`，上海，免费体验版；未升级套餐 |
| 云托管入口 | `yongsen-api`：Node.js 轻量网关及 Vue 管理后台，PUBLIC / MINIAPP 已启用；0.25 CPU / 0.5 GB，实例 0–1 |
| Java API / 长任务执行 | 独立 SCF `yongsen-training-api`，Web / CustomImage，3072 MB，执行 780 秒、初始化 90 秒、临时磁盘 2048 MB，总并发上限 4；无预置并发 |
| 异步桥接 | 独立 SCF `yongsen-training-jobs`，Nodejs20.19，128 MB / 900 秒，并发上限 1，错误重试 2 次、事件 TTL 3600 秒；无定时触发器 |
| API 函数 URL | `https://1304903095-gfmwytb7n6.ap-shanghai.tencentscf.com`；公网入口由业务 Bearer、角色和任务归属校验保护，内部 worker 另用强随机密钥 |
| 云 MySQL | `cynosdbmysql-d1043vmo`，MySQL 8.0，`172.17.0.10:3306`；公网关闭，0.25–0.5 CPU / 1 GB，30 分钟无访问自动暂停 |
| 网络 | Java SCF 已绑定 `vpc-481u4ax8 / subnet-269ld31z`；业务 JDBC 登录、读写已成功 |
| COS | 私有桶 `796f-yongsen-training-d0eldief700a7cd-1304903095`，上海；视频、原 PDF 与页图均存储在 COS |

采用“微信云开发入口 → SCF Java 后端 → 云 MySQL”的实际运行结构。此前云托管直连 VPC 的配置未生效并实测超时，故将需要 MySQL 的 Java 放在已验证能进入 VPC 的独立 SCF；云托管负责小程序调用入口和管理页面。没有购买 199 元标准版、CFS、API 网关、CDN或企业镜像仓库。

云托管和 SCF 共用构建定义，可按组件独立发布。当前网关版本 005、Java SCF 保留版本 004；前者覆盖入口启动 Node 网关，后者保留 Java 默认入口。网关不持有数据库或云账号密钥；仅转发业务 API、健康检查和微信回调，阻断本地模拟与内部 worker 路径，过滤外来身份头，素材直接上传 COS。实现参数见 [部署说明](11-wechat-cloudbase-deployment.md) 和 [运行手册](../deployment/serverless/README.md)。

## 已发布版本

| 内容 | 可核对版本 |
| --- | --- |
| Java 初始构建 | 版本 `004`，实际 buildId `2608877055`，2026-10-18 09:49:59 完成 |
| 当前网关构建 | 版本 `005`，buildId `2608881069`，2026-10-18 12:47:15 镜像推送完成，已切换 100% 流量 |
| 网关镜像 | `ccr.ccs.tencentyun.com/tcb-100002680897-zxnl/ca-dhttjkzw_yongsen-api:yongsen-api-005-20261018124254` |
| 网关镜像摘要 | `sha256:b0ab64553b0341ff06878efa7c0407441f2f37c12ce86600b6a9f63391e488f0` |
| Java SCF 镜像 | `ccr.ccs.tencentyun.com/tcb-100002680897-zxnl/ca-dhttjkzw_yongsen-api:yongsen-api-004-20261018094533` |
| Java SCF 镜像摘要 | `sha256:2ab1ba38273b39a1db89e9e1ba74cf14ed653e43665c366f03e35fe01204120b` |
| 后端 Jar SHA-256 | `b8a93627f59a76a684a7cb21e301009a6c612d20272e4a1194cd819e1eb09dd1` |
| 初始镜像构建源码包 SHA-256 | `f712f91b67104580b78d620a9a26f41566a793117c6da4c363adf272242685a8` |
| 已部署事件桥接 ZIP SHA-256 | `49c4370fb5dd38586825c697d61f07668e4c23b283be87627e54c3e7b7535457` |

事件桥接在镜像发布后独立更新：腾讯云 Node.js 20 的剩余时间辅助方法实测抛出 `client.ms_elapsed is not a function`。现已容错使用固定 840 秒 HTTP 截止时间（低于函数 900 秒），仍等待 worker 真正执行完成再确认；新增回归测试通过。部署后真实调用返回 `done`，PDF 随后通过 Event 异步调用自动完成。不能仅凭 SCF 的 `InvokeResult: 0` 判断业务成功，必须同时核验错误字段和数据库作业终态。

## 云端验证结果

| 检查 | 实际结果 |
| --- | --- |
| 管理员登录及 SQL | 云端真实管理员登录 200；人员、任务、教材与通知配置 API 200；已发生材料状态和审计事务写入 |
| API 权限 | 匿名管理接口 401；网关本地模拟及内部 worker 路径 404 |
| MP4 | COS 签名 PUT 200、CORS 指定 origin 正确；异步处理 READY，原件 SHA-256 一致；签名读取 Range 返回 206 |
| PDF | COS 签名 PUT 200；异步渲染 READY，原件 SHA-256 一致，1 页页图读取 200 |
| 存储权限 | 业务账号对象读写/复制通过；前缀外写入 403、列桶 403；临时权限探测对象已清理 |
| 订阅消息配置 | 正式模板已通过管理员 API 保存，生成审计记录；配置接口 `configured: true` |
| 微信回调入口 | 使用本机私密 token 构造的 GET 签名校验返回 200 / 原样 echo；公众平台尚未配置该 URL，不代表真实消息回执 |
| 诊断资源 | 本轮临时网络及镜像探测函数已删除，保留正式 API 与 jobs 函数 |

初次部署创建了两条明确命名为“部署验收样本（未发布）”的 MP4 / PDF 材料，没有导入本地测试库、人员、培训任务或学习完成记录。432 修复验收另通过真实管理员 API 新建一名明确标记的验收员工，完成验证后已停用；未生成邀请码、任务或学习记录。样本的手机兼容确认仍为 false，不可据此声称真机学习已验收。

## 微信接入状态

| 配置 | 当前值 / 状态 |
| --- | --- |
| AppID | `wx505c1dc50f1f7099` |
| 模板 | 新任务发布通知，公共 ID `737`；一次性订阅 |
| 私有模板 ID | `H6PhT6NXzHPDJ8RHAP9hL0vVhgt3a2Dntk60PQY4TKI` |
| 字段 | `thing1 → title`，`time20 → dueAt`；下发任务必须填写截止时间 |
| 生产工程 | `miniprogram/release/production-3XjvzA`，真实 AppID、cloud transport、目标环境和服务名；不含服务端秘密、禁用模拟入口 |
| 开发者工具 | 已导入生产工程并编译到登录页面，未上传体验版 |
| 真实云调用 | 开发者工具 `wx.cloud.callContainer /health` 返回 `404 / INVALID_HOST`，尚未到达后端 |
| 账号关联 | 用户已反馈完成身份验证并同意关联永森油服；后续授权页操作受限，最终小程序关联结果尚未读回，不能视为已连通 |
| 真机验收 | 真实 `wx.login`、员工绑定、订阅授权、收件、任务跳转和学习流程均待验证 |

腾讯云环境需要关联到小程序才能从开发者工具调用。按官方流程，先在当前腾讯云账号关联该小程序，再到开发者工具云开发的环境管理选择已有腾讯云环境；不能通过关闭域名检查或模拟 openid 解决关联问题。[已有腾讯云环境关联流程](https://cloud.tencent.com/document/product/876/57380)、[小程序访问云托管](https://docs.cloudbase.net/run/develop/access/mini)。

`callContainer` 免配置 API 服务器合法域名；视频与页图使用 COS HTTPS 签名地址，相关微信媒体/下载域名仍需实际配置和真机验证。模板已可用不等于用户已订阅；发送接口接受、结果回调、手机收件及点击确认必须分别记录，不能把未知结果改记为送达。

## 权限、数据库与费用边界

- `SCF_QcsRole` 已关联基础策略和镜像拉取策略。API-only 子账号 `yongsen-runtime` 现仅关联 `YongsenTrainingRuntime`；初始无关策略已移除，未授予控制台登录。COS 限指定桶 `uploads/` 和 `materials/`；SCF Invoke 按官方能力仅支持操作级，不能限制单函数，未授予函数管理或 CAM 权限。[官方权限粒度](https://cloud.tencent.com/document/product/598/70005)。
- 数据库 `yongsen_app` 仅目标库 SELECT / INSERT / UPDATE / DELETE，最大连接数 20。正式 schema 为 59 条 DDL、20 张 InnoDB 表。原生 JDBC 已连通，云端事务回滚/并发、闲置后应用唤醒和备份恢复尚需专项验收。
- 实际 MySQL 握手不声明 TLS 能力，控制台 SSL 操作当前不可用。JDBC 默认 PREFERRED 通过私有 VPC 连接；**当前数据库链路没有获得 TLS 加密证明**。未设置 `sslMode=DISABLED`、`allowPublicKeyRetrieval` 或关闭证书校验，也未开放数据库公网。
- COS CORS 仅增加两个实际管理后台 HTTPS origin，保留平台官方来源；`uploads/` 配置 1 天过期，`materials/` 不设自动过期。运行凭据只存在本机 0600 私密配置及云端运行配置。
- 免费环境不代表整套服务零费用。独立 SCF 调用/执行/流量、临时磁盘超出免费部分等按量计费；本轮没有升级套餐、开启预置并发或常驻实例。此前约 20 元/月只是基础档目标，尚无账单证明总费用能限制在 20 元内。[SCF 定价](https://cloud.tencent.com/document/product/583/12281)、[临时磁盘计费](https://cloud.tencent.com/document/product/583/113039)。

## 2026-10-18 请求失败 432 / 员工保存修复

用户报告工作台显示“请求失败（432）”、员工无法保存。实际复现：5 个并发认证请求中，4 个成功，第 5 个返回平台 `Function [yongsen-training-api] concurrency exceeded reserved quota 12288MB`。单实例 3072 MB、最大独占配额 12288 MB，允许同时执行 4 个请求；首页与教程的并发加载会超过上限。[官方状态码定义](https://cloud.tencent.com/document/product/583/42611)。

网关版本 005 限制到 SCF 的并行连接为 3，总在途上限 32，其余请求有界排队，排队计入原有 55 秒截止时间。给独立事件 worker 留出一个执行槽位；取消已断开的排队请求，不重放写入、不合并身份或缓存私人响应。队列满返回 503 中文提示。Java SCF 并发和内存额度、数据库权限、实例最小值保持不变，未购买扩容。

云端 005 已正常并接收 100% 流量。通过同一公网管理入口验证首页查询与员工创建同时执行，连续 30 次实际 API 请求全部 HTTP 200；验收员工 `DEPLOY-432-20261018` 只创建一次，读回一致后已停用。首次一组请求最长 20.7 秒，后续请求较快；这不是高负载容量或冷启动延迟承诺。用户原有员工和任务未修改。验收材料见 [复现](../.local/cloudbase-deployment/gateway-432-reproduction.json)、[云端结果](../.local/cloudbase-deployment/gateway-432-cloud-verification.json)、[部署读回](../.local/cloudbase-deployment/gateway-432-detail.json)。本次未完成远程浏览器点击验收，云端使用与页面相同的认证 API；本地真实浏览器 E2E 已通过。

修复源码归档 SHA-256：`e146afcb02bb05f2f327c24c5aece9cf9021fdb44d69d25f6d690709fabbf737`。本次只发布入口网关，Java 和事件桥接未重发。

## 本地测试与可复核证据

[最新完整本地验收](../.local/verification/2026-10-18T04-42-58-578Z/verification.json)通过：后端常规 175 项（另 2 项 MySQL 专项单独运行）、Web 10 项、小程序逻辑 34 项、部署契约 **23 项**、Chromium E2E **16 项**、停机备份恢复。新增覆盖突发并发与员工写入不重放、排队上限及超时释放、客户端断开取消排队写入，并已将网关测试接入 `npm run verify`。 [隔离 MySQL 专项](../.local/mysql-check/2026-10-17T14-36-12-205Z/verification.json)中 schema、持久作业和 8 项 API E2E 通过。本地微信渠道为模拟，不是手机收件证明。

| 本机证据 | 范围 |
| --- | --- |
| [api-current.json](../.local/cloudbase-deployment/api-current.json) | 已脱敏的正式函数状态、摘要、VPC、磁盘与环境变量名 |
| [cloud-endpoints-verification.json](../.local/cloudbase-deployment/cloud-endpoints-verification.json) | 实际 HTTP、鉴权与回调签名检查 |
| [cloud-media-verification.json](../.local/cloudbase-deployment/cloud-media-verification.json) | MP4 / PDF 上传、处理、散列与读取 |
| [job-recovery-verification.json](../.local/cloudbase-deployment/job-recovery-verification.json) | 修复前遗留作业的管理员恢复与终态 |
| [job-sync-result-v2.json](../.local/cloudbase-deployment/job-sync-result-v2.json) | 修复后真实 SCF bridge 返回 done |
| [runtime-permission-restriction.json](../.local/cloudbase-deployment/runtime-permission-restriction.json) | 业务账号权限收敛 |
| [runtime-cos-verification.json](../.local/cloudbase-deployment/runtime-cos-verification.json) | 真实 COS 授权与拒绝路径 |
| [notification-config.json](../.local/cloudbase-deployment/notification-config.json) | 模板与后端配置应用记录 |

仅分享已核对脱敏的证据文件，不整体分享 `.local`；运行配置、平台原始响应与登录会话可能含秘密。

## 仍需完成

1. 核验用户完成身份验证后的最终小程序关联结果，完成现有云开发环境关联，验证 `callContainer` 和真实登录。
2. 配置公众平台消息回调、相关媒体域名，上传体验版；由指定微信用户授权订阅并完成接收、点击、学习和本人确认，后台核对导出记录。
3. 补齐云端数据库暂停唤醒、故障恢复及备份恢复验证，核对实际费用。上述完成前，不将整体 MVP 标为发布验收结束。
