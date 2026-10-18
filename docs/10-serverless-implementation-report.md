# 按量部署适配与本地验收报告

> 本文是 2026-10-17 适配阶段的历史报告，保留该轮结果。之后已完成云后端发布并增加回归验证，当前状态见 [12 云部署记录](12-cloud-deployment-status.md)，整体结构见 [13 架构和部署总览](13-project-architecture-and-deployment.md)。

执行日期：2026-10-17，北京时间。对应已确认的 **SCF + TDSQL-C Serverless MySQL 8.0 + 私有 COS** 方案。本轮代码、源码部署包和本地验证已完成；腾讯云资源尚未创建，镜像尚未构建，真实微信登录和通知送达仍待验收，因此整个上线任务尚未完成。

## 1. 实现结果

| 部分 | 当前实现 |
| --- | --- |
| API 与管理后台 | Java 17 SCF Web 镜像入口，独立 `serverless` profile，监听 9000；运行参数缺失或混入本地模式时拒绝启动 |
| 云 MySQL | MySQL 8 schema 包含持久作业表；使用 `DriverManagerDataSource` 在操作或事务结束后物理关闭连接，生产不自动建表 |
| COS 教材 | 15 分钟签名直传，约束长度、类型、ACL；完成上传时按 ETag 条件复制固定原件，旧上传 URL 不会改写已登记材料 |
| 材料处理 | 持久任务、租约、执行令牌与版本检查；下载原件后实际计算 SHA256、检查视频和渲染 PDF；临时目录丢失不影响云端原件 |
| 通知执行 | 数据库事务提交后触发 SCF，通知每批最多 5 条并保存游标；不使用云端定时轮询，UNKNOWN 不自动重发 |
| 失败恢复 | 管理后台“通知设置”内查看培训处理状态，未完成优先、支持分页；管理员可恢复失败、未调度成功或租约已过期的作业 |
| 微信小程序 | 生产构建支持 HTTPS `wx.request`，保留实际 `wx.login` 换取服务端身份、人员绑定和原有学习流程 |
| 部署与凭据 | 源码白名单打包和归档 SHA256 校验；运行密钥通过私密环境注入，不信任请求中的云凭据头 |

部署参数、创建顺序与私密变量见 [SCF 部署说明](../deployment/serverless/README.md)，请求与响应见 [运行 API 契约](runtime-api.md)。本次不增加考试、审批或定时自动催办。

## 2. 本次验证

最终后端 Jar SHA256：

```text
4b872e3e079cca6af5c1f242dc039eef2f1599dc657eddbe41329cbb2385e372
```

完整验收与隔离 MySQL 均运行这一份 Jar，测试数据与日常预览分开。

| 验证 | 实际结果与范围 |
| --- | --- |
| 后端测试与打包 | 168 项通过；另外 2 项 MySQL 专项在常规测试中跳过，随后在真实本地 MySQL 中执行通过 |
| Web 单测 / 生产构建 | 10 项通过，TypeScript 检查与 Vite 构建通过 |
| 原生小程序逻辑 | 34 项通过，含 HTTPS 生产构建参数、学习采集器与登录逻辑；不是本轮真机 E2E |
| SCF 部署入口与事件桥接 | 11 项通过，含非法配置拒绝、固定 worker 地址、密钥隔离、完成/失败/重试、响应超时与大小边界 |
| H2 / Chrome 完整端到端 | 16 项通过，含人员绑定、材料、发布、模拟通知、真实时间视频/PDF 学习、本人确认、导出、教程和后台任务恢复分页 |
| 隔离 MySQL 任务专项 | 2 项通过，覆盖事务回滚、重复入队、租约与执行版本隔离、超过 100 条完成记录时失败作业可发现及分页 |
| 隔离 MySQL 业务 API E2E | 8 项通过，使用正式 schema 与同一 Jar，微信使用模拟提供方 |
| 停机备份与恢复 | 通过，恢复后核对培训完成时间、导出、材料散列与可读取性、管理员教程状态；这是 H2 本地恢复，不是云库备份验收 |
| 源码与事件 ZIP | 80 个源码文件归档、逐文件 SHA256 与解包一致性检查通过，私密配置和本地测试数据未打入包 |

证据：

- [完整验收结果](../.local/verification/2026-10-17T09-36-09-736Z/verification.json)、[Chrome 报告](../.local/verification/2026-10-17T09-36-09-736Z/browser-report/index.html)。
- [MySQL 验收结果](../.local/mysql-check/2026-10-17T09-37-11-971Z/verification.json)、[MySQL API 报告](../.local/mysql-check/2026-10-17T09-37-11-971Z/report/index.html)。
- [恢复核验](../.local/verification/2026-10-17T09-36-09-736Z/evidence/restore-result.json)。
- 最终完整日志 `.local/serverless-full-verification.log`；MySQL 日志 `.local/serverless-mysql-verification.log`。重新运行会覆盖这些便捷日志，带时间戳的证据目录保留独立结果。

COS / SCF 的服务端集成测试使用可控替身，实际执行文件校验、视频探测、PDF 渲染和持久状态流转，覆盖临时目录清空后新 worker 处理、旧上传 URL 隔离、媒体权限和 Range。它们验证代码逻辑，不能替代真实 COS 签名/CORS、SCF 网络与平台重试验收。

## 3. 部署包

- [源码构建包](../.local/serverless-artifacts/2026-10-17T09-38-37-660Z-f0e62102/yongsen-training-serverless-source.tar.gz)。SHA256：`94120d1b14c3b4d56b167482090a097061d6a485ec62787da37740b199882f68`。
- [事件桥接 ZIP](../.local/serverless-artifacts/2026-10-17T09-38-37-660Z-f0e62102/event-bridge.zip)。SHA256：`de210157cb3f5bfc251be3b6050bc9b4c7d08156dc972084d97bce639ef29c6a`。
- [逐文件清单](../.local/serverless-artifacts/2026-10-17T09-38-37-660Z-f0e62102/manifest.json)。源码包是 Linux amd64 镜像构建输入，不是已经构建或发布的镜像。

本机未安装 Docker；镜像构建、启动和云端部署未执行。没有创建付费资源，没有迁移本地测试数据，也没有产生微信线上版本。

## 4. 云端继续执行所需条件

用户已有腾讯云账号、暂无域名。目前本地部署凭据文件 `.local/serverless-access/credentials.json` 已准备为仅当前用户可读写，SecretId / SecretKey 尚未配置。需在该文件配置有本项目资源管理权限的腾讯云子账号，或提供已有凭据文件的本地路径；密钥不通过聊天提供。资源管理凭据与应用运行所需的最小权限 CAM 凭据分开。

取得账号访问后，按部署说明核验地域和资源配额、创建独立空 MySQL / 私有 COS / SCF 及镜像仓库、构建发布镜像，再执行：

1. 默认函数 HTTPS URL 的健康检查、管理员登录、真实微信 code 登录、COS 直传与视频 Range。默认地址先用于接口联调；管理后台 HTML 展示、微信 request / 媒体合法域名应以实际账号验证结果为准。
2. MySQL 空闲连接归零、自动暂停、唤醒和断连恢复；不能仅凭配置声明已经实现节省计算费用。
3. 最大素材与慢存储下的处理时间、至少 2 GiB 临时盘配额、平台强杀后的临时文件与租约恢复、并发事件幂等。
4. 云数据库和 COS 原件的备份恢复，以及实际调用/存储/流量费用。按 [费用模型](09-serverless-cost-plan.md) 条件预留 100–150 元/月，不作为费用上限。
5. 正式域名和小程序平台配置、订阅模板及字段、回调配置、个人微信主动授权与真实收件、点击正确任务和完整学习。订阅消息模板仍未配置，真实通知送达是 MVP 必验项。

## 5. 本地继续使用

新版预览已启动在 `http://127.0.0.1:5173`，继续使用 `.local/preview-20261017/` 原数据；微信渠道为模拟。复跑命令：

```sh
npm run verify
npm run verify:mysql
node scripts/package-serverless.mjs
```

本机 Maven 如受既有镜像设置影响，可通过 `TRAINING_MAVEN_SETTINGS` 指定本地 settings 文件；该路径不会进入部署包。其他机器按自己的 Maven 配置执行。重新运行原生开发者工具 E2E、镜像测试及云端真机验收应分别记录新证据，不复用历史结果作为已通过证明。
