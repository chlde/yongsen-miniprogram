# 永森油服 · 企业培训平台

本工程已实现管理后台、原生微信小程序、Java 服务和本地验收工具。业务流程为 **建立人员与核对微信绑定 → 指定人员发布任务 → 微信提醒 → 视频/PDF 学习 → 本人确认 → 查询、导出和打印记录**。

本地可运行的模拟微信流程与真实微信上线验收分别记录。正式学员入口是 `miniprogram/`；浏览器学员入口只用于本地预览与自动化测试，不作为真实微信身份或通知送达证明。账号接入、部署和恢复的完整操作见 [05 本地运行与微信接入](docs/05-local-and-wechat-setup.md)。

**当前架构与发布入口见 [13 项目架构和部署总览](docs/13-project-architecture-and-deployment.md)。** 管理后台与云后端已部署，真实微信登录、通知送达和完整手机学习流程仍待验收；实际资源、版本和证据见 [12 云部署执行记录](docs/12-cloud-deployment-status.md)。

本仓库包含前端、Java 后端、原生小程序、测试及部署源码。运行密钥、本地数据库、依赖目录和生成的发布包不入库；文档中指向 `.local/` 的验收证据仅在原开发机器保存，克隆后可按下述命令重新生成本地测试报告。

## 本地启动

需要 Java 17、Maven、Node.js 20.x（至少 20.19）或 22.12 及以上版本、npm、ffprobe、pdfinfo、pdftoppm；生成验收样本还需要系统 ffmpeg，浏览器验收需要 Google Chrome。首次运行在项目根目录执行（Playwright 的 FFmpeg 与系统 ffmpeg 分开安装）：

```sh
npm ci
npx playwright install ffmpeg
npm run setup
npm run dev
```

`setup` 安装 Web 和小程序依赖、构建后端并生成测试样本。`dev` 启动后台与 Web：

| 入口 | 地址与说明 |
| --- | --- |
| 管理后台 | `http://127.0.0.1:5173`，本地管理员 `admin` / `LocalTrain123!` |
| 浏览器学员预览 | 同一页面选择“本地学员预览”，例如使用 `alice` 作为模拟身份 |
| Java API | `http://127.0.0.1:8080/api/v1` |
| 本地持久数据 | `.local/dev/`，包含数据库、原材料与 PDF 页图 |
| 原生小程序 | 微信开发者工具导入 `miniprogram/`，操作见 [小程序说明](miniprogram/README.md) |

先由管理员建人并生成个人邀请码，学员申请绑定后由管理员核对，再上传材料、预览确认、选人发布。默认账号和模拟身份仅供本地环境；网页与 API 都是回环地址时才显示浏览器学员预览。不要将已有业务数据目录用于测试。后端使用构建后的 jar，修改 Java 后需重新构建、重启；Web 开发页面自动更新。按 Ctrl+C 停止本地服务；默认端口被占用时先停止原服务再启动。

## 验证与交付证据

最新部署和回归结果见 [12 云部署执行记录](docs/12-cloud-deployment-status.md)；适配阶段的版本与部署包见 [10 历史适配验收报告](docs/10-serverless-implementation-report.md)，此前原生与负载测试记录见 [06 历史本地验收报告](docs/06-local-test-report.md)。本地测试通过与真实微信收件验收分别记录，真实收件尚未通过。

```sh
npm run verify
```

该命令测试并构建各端，在独立数据目录运行 API 和真实 Chrome 界面流程，以真实时间播放视频、阅读 PDF、完成本人确认和导出，最后执行停机备份、恢复及记录一致性核验。测试服务使用 API `18080`、Web `15173`，与日常开发数据隔离。

每次运行的结果保存在 `.local/verification/<时间>/verification.json`，浏览器报告与诊断分别在同目录的 `browser-report/`、`browser-results/`。单独运行 `npm run test:e2e` 才默认输出到根目录 `playwright-report/`、`test-results/`。是否通过以对应运行生成的报告为准。原生开发者工具端到端验证单独执行 `npm --prefix miniprogram run test:native`，连接条件见文档 05。

## 已实现范围

- 管理员、学员两个角色；个人邀请码绑定与管理员核对，停用及撤销绑定后阻止继续学习。
- 上传真实 MP4/PDF，提取视频信息、转换 PDF 页图、预览兼容性确认；发布后固定人员、材料与学习规则。
- 首次任务通知和人工催办，分别记录发送结果、可关联的送达回执、任务打开时间；未知发送结果需新授权与明确确认后才能再次提醒。
- 视频按有效观看区间去重，PDF 按逐页前台停留与实际可见区域核验；支持断点续学、异常暂停和补学。
- 达到全部材料要求后由学员本人确认，保留学习日志、材料原件、确认快照，提供 CSV 和中文个人记录打印页。

不包含在线考试、现场考核管理、自动定时催办、多级审批、外部审核门户或第三方存证。Word/PPT 需先转 PDF；本期不转码视频。

## 生产配置与真实微信门禁

当前已经部署 **CloudBase 云托管网关 + 独立 SCF Java API / Event 桥接 + 云 MySQL 8.0 + 私有 COS**。网关承载 Vue 后台与小程序云调用入口，Java 通过私有 VPC 访问 MySQL，视频/PDF 直接签名上传 COS。实际操作见 [11 微信云开发部署说明](docs/11-wechat-cloudbase-deployment.md) 和 [SCF 运行手册](deployment/serverless/README.md)。当前网关版本 005、Java 镜像版本 004，事件桥接独立 ZIP 发布；此前的 [常驻容器/CFS 资产](deployment/README.md) 保留为旧方案。

管理入口：[永森油服培训管理后台](https://yongsen-api-330499-11-1304903095.sh.run.tcloudbase.com/#admin)。云端管理员登录、业务 API、员工创建读回、MP4/PDF 上传与异步处理已验证。CloudBase 目前为免费体验版，独立 SCF 等资源费用仍须按账单核对；历史约 20 元/月基础档目标不是整套系统总费用上限。

`serverless` profile 不运行定时轮询，使用 `DriverManagerDataSource` 在数据库操作或事务结束后物理关闭连接，避免空闲连接阻止云 MySQL 暂停。生产通过内网 JDBC 访问独立云库，使用最小权限业务账号；首次建空库并执行配套 schema，不导入本地测试数据。自动暂停与唤醒、云数据库及 COS 教材备份恢复仍需实际验收。

环境变量参考 [.env.example](.env.example) 和 SCF 运行手册，实际配置以云部署记录及私密运行环境为准；示例文件不会被脚本自动加载。Java 生产镜像固定 `serverless` profile；`npm run dev` 使用本地 H2 和模拟微信，不用于生产部署。AppSecret、数据库密码、数据加密密钥、管理员密码、worker 密钥和最小权限 CAM 业务凭据只通过私密运行环境提供，不写入仓库、构建产物、浏览器或小程序。网关仅配置 Java 上游地址，不持有业务秘密。

当前构建定义包含 Web 静态文件与 Java API；云托管选择 Node 网关入口，SCF 选择 Java 入口，按组件分别发布。后台按同源 `/api/v1` 访问，视频和 PDF 页图经短期签名直接访问 COS。独立构建 Web 时显式指定 API 基址：

```sh
VITE_API_BASE_URL=/api/v1 npm --prefix web run build
```

`TRAINING_PUBLIC_BASE` 应为实际 HTTPS API origin，不含 `/api/v1`。若单独部署 `web/dist/` 并与 API 分域，构建时使用完整 HTTPS API 基址，后端 `TRAINING_ALLOWED_ORIGINS` 配置精确的实际 Web Origin，COS CORS 同步允许该来源及直传签名所需请求头；不使用通配符。生产不可沿用 `127.0.0.1:8080`。

当前无需自有 API 域名，管理端使用 CloudBase 默认 HTTPS 入口；小程序生产方案使用 `wx.cloud.callContainer`。构建使用 `npm --prefix miniprogram run build:production -- --transport cloud --env "$WECHAT_CLOUD_ENV" --service "$WECHAT_CLOUD_SERVICE" --public-https "$TRAINING_PUBLIC_HTTPS"`，具体公开参数见文档 11。身份仍通过 `wx.login` 换取本系统 token，COS 媒体/下载域名需要单独配置与真机验证。

**真实微信通知收件与跳转属于 MVP 必验收项。** 必须使用现有“永森油服”实际 AppID、账号批准的模板和指定个人微信主动授权，验证手机收到任务卡片、点击进入本人的正确任务。API 成功、模拟回调、开发者工具通过均不能替代真实手机收件，也不能据此宣称已上线。

当前 AppID 和“新任务发布通知”模板已配置，生产工程已在开发者工具编译，未上传体验版。用户已反馈完成腾讯云身份验证并同意关联小程序，但最终关联结果尚未读回；仍需核验环境关联、真实登录、消息回调、相关媒体域名和手机闭环。后台会话链接及其参数不进入代码、文档和日志。

最新企业级界面与页面组织见 [07 产品改版](docs/07-enterprise-product-design.md)，突出安全生产、岗位技能和员工能力培训。

## 代码与设计入口

| 目录或文档 | 用途 |
| --- | --- |
| `backend/` | Java 17 / Spring Boot，认证、材料、任务、学习核验、通知、记录与存储 |
| `web/` | Vue 3 / TypeScript 管理后台，以及明确标记 LOCAL 的浏览器学员预览 |
| `miniprogram/` | 原生微信学员页面、订阅授权和视频/PDF 采集器 |
| `scripts/`、`tests/e2e/` | 启停、样本、隔离验证、备份恢复和端到端用例 |
| [01 产品与架构](docs/01-product-and-architecture.md) | MVP 范围、角色、页面和任务状态 |
| [02 学习核验与微信推送](docs/02-learning-and-notification.md) | 完成判定、续学、授权、发送和失败处理 |
| [03 数据模型与接口](docs/03-data-and-api.md) | 核心数据与接口设计 |
| [运行接口契约](docs/runtime-api.md) | 当前实现使用的请求、响应和本地联调约定 |
| [04 开发与验收](docs/04-delivery-and-acceptance.md) | 验收场景和账号准备清单 |
| [05 本地运行与微信接入](docs/05-local-and-wechat-setup.md) | 部署配置、原生验证、真实收件和备份恢复 |
| [06 本地验收报告](docs/06-local-test-report.md) | 实际版本、端到端、并发和恢复证据，以及尚待真机验收的项目 |
| [07 企业培训产品改版](docs/07-enterprise-product-design.md) | 新页面结构、视觉规范、真实统计口径与本轮界面验证 |
| [08 管理后台首次使用教程](docs/08-admin-onboarding.md) | 六步操作指引、服务端状态与重播方式 |
| [09 按量部署与费用预算](docs/09-serverless-cost-plan.md) | 早期选型与费用模型，保留为历史方案 |
| [10 按量适配验收报告](docs/10-serverless-implementation-report.md) | 适配阶段的本地验证与部署包历史记录 |
| [11 微信云开发部署说明](docs/11-wechat-cloudbase-deployment.md) | 当前云入口、Java 函数、数据库与小程序的部署步骤 |
| [12 云部署执行记录](docs/12-cloud-deployment-status.md) | 已部署资源、实际版本、验证证据与未完成项 |
| [13 项目架构和部署总览](docs/13-project-architecture-and-deployment.md) | 整体架构图、业务模块、数据、工程结构和发布方式 |
| [SCF 部署说明](deployment/serverless/README.md) | 镜像与事件包、私密配置、云资源创建顺序和上线验证边界 |

记录面向客户或行业安全审核，结论为“已完成本任务学习要求”。它支持学习过程追溯，不能绝对证明本人持续专注或理解；客户另有考试、实操要求时仍需单独实施。正式使用前应确认客户认可的记录内容、学时规则和保存期限；本工程不硬编码法定保存年限。
