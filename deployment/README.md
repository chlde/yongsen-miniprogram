# 微信云环境部署：原容器方案

> 2026-10-17 选型更新：后续按用户“尽量按调用次数收费”的要求，采用 [SCF + Serverless MySQL + COS 方案](../docs/09-serverless-cost-plan.md)。本目录现有资产仍是常驻容器方案，保留作为可核查的实现基线；下述 CFS、持续运行和固定规格数据库配置不代表最新目标，也不能直接作为云函数部署包发布。

本目录把现有 Java 17 / Spring Boot API 与 Vue 管理后台构建为一个 Linux 容器。生产数据库选用腾讯云托管的 **TencentDB for MySQL 8.0**，另依赖持久 CFS 文件存储和真实微信登录配置。云环境、数据库、存储、访问权限、域名和费用均需在实际账号中核实；这些文件不会自动创建或购买云资源，也不表示已部署成功。

微信云托管与云开发 CloudBase 的控制台入口、环境和可开通能力可能不同。以下 CFS、请求限制、计费条款来自当前 **CloudBase 云托管** 官方文档；若实际选用微信云托管独立环境，应先在对应环境核实同等能力，不能直接套用套餐或默认域名结论。如果该环境仅提供内置数据库或不能接入目标 VPC，必须先核实实际网络连通方式、MySQL 8.0 引擎和 JDBC 能力；确认前不得宣称 TencentDB 已开通或可连接，也不改用容器内或本机数据库代替。[云托管说明](https://docs.cloudbase.net/run/introduction)

## 最小部署结构

- 一项容器服务：Java 17、`ffprobe`、`pdfinfo`、`pdftoppm`，以及构建后的管理后台。Vue 以 `/api/v1` 访问同域 API；无需另开静态网站服务。镜像同时包含 ffmpeg，当前业务只用 ffprobe 检查视频，不会自动转码。
- 一套独立生产 **TencentDB for MySQL 8.0 云数据库**：与容器服务选择同地域、同 VPC，Java 通过实例内网地址和实际端口使用 JDBC 连接；安全组仅允许服务实际来源访问数据库端口。不自建 MySQL 容器，不连接开发者本机，不为应用开放数据库公网入口。这里使用 MySQL 协议连接，不使用 CloudBase 数据库 SDK。[官方云托管访问云上 MySQL](https://cloud.tencent.com/document/practice/1243/49231)
- 一个持久 CFS 挂载：挂到容器 `/mnt/training`，存放原材料、PDF 页图等文件。CFS 需与服务处于支持的同地域网络并配置 VPC、子网及权限组；容器 UID/GID `10001:10001` 必须能读写所挂目录。入口脚本要求该目录确为 NFS 挂载，缺失时拒绝启动，避免把资料写入临时容器磁盘。[官方 CFS 挂载步骤](https://docs.cloudbase.net/en/run/deploy/configuring/storage/cfs)

首次由独立初始化账号在空生产库执行 `backend/schema-mysql.sql`。后台服务使用专用数据库账号，仅授予本应用表所需的 `SELECT`、`INSERT`、`UPDATE`、`DELETE` 权限，并限制允许连接的服务来源；不授予建库建表、授权管理等权限，不将 root 或初始化账号作为运行账号。数据库密码通过平台私密配置注入。[腾讯云数据库业务账号](https://cloud.tencent.com/document/product/236/35794)

当前媒体处理只有进程内互斥，尚未实现多副本处理租约。首次部署应只运行 **1 个服务版本、1 个实例**；变更版本时先停止旧版本，再启动新版本，接受短暂维护窗口，不能将“每版本最多 1 个实例”误当作总共只有 1 个实例。扩成多副本前需另行验证处理竞争。

采用持续运行模式，关闭缩容到 0。现有材料处理与通知调度依赖后台任务；官方开发说明提醒请求完成后的后台 CPU 不应直接假定可用。必须在选定运行模式中实测：上传后关闭网页，等待后再次进入仍能看到材料处理完成；后续通知调度同样验证。若该模式无法保证后台执行，需调整任务执行方式，不能仅凭容器健康就交付。[后台任务约束](https://docs.cloudbase.net/run/develop/developing-guide)

## 构建

在仓库根目录执行；先完成项目现有验证，再构建部署产物：

```sh
npm ci
npm run setup
npm run verify
docker build --platform linux/amd64 -f deployment/Dockerfile -t yongsen-training:release .
```

Dockerfile 使用 Node 22 构建网页，再把 `web/dist` 放入 Spring 静态资源目录，最后只复制可执行 jar 到 Java 17 运行镜像。构建过程不注入 AppSecret、数据库密码或业务数据。根 `.dockerignore` 采用源码白名单，排除 `.local`、现有数据库、日志、测试产物、私密配置和证书。不要直接将整个工作目录打 ZIP 上传到云控制台；采用 Docker 构建上下文或严格按同一白名单生成源码包。

平台支持上传源码并按 Dockerfile 构建时，可在仓库根目录生成经过检查的源码包，不要求本机安装 Docker：

```sh
node scripts/package-deployment.mjs
```

脚本将产物写入已被 Git 忽略的 `.local/deployment-artifacts/<时间戳>/`，包含 `yongsen-training-source.tar.gz`、逐文件 `manifest.json`、`SHA256SUMS` 及可检查的 `source/` 目录。解压后平台构建上下文选择 **包根目录 `.`**，Dockerfile 路径填写 **`deployment/Dockerfile`**，镜像平台选择 **`linux/amd64`**；`backend/schema-mysql.sql` 同时放入包内供空库初始化使用，但不会自动执行。

打包只读取明确白名单的应用源码，拒绝符号链接，排除 `.env`、私密配置、测试文件和运行数据；在生成后比对 tar 条目，并重新解包核验每个文件的 SHA-256。对打包文本进行疑似字面量凭据检查，发现问题只输出文件位置，不回显值；脚本不会读取真实运行环境的秘密或将变量引用替换为秘密。生产凭据仍需单独通过平台私密配置注入。源码检查通过不等于云端构建、持久存储或真实微信验收已完成。

Mac Apple Silicon 构建时仍须指定 `linux/amd64`；云托管官方部署排错文档明确说明不支持 ARM 镜像。也可使用受控 CI 或平台在线 Dockerfile 构建，再通过控制台部署镜像。镜像仓库、登录与推送授权由实际环境确定。[镜像架构及 Java 版本要求](https://docs.cloudbase.net/run/faq/deploy)

本机当前未发现 Docker 或 CloudBase CLI，因此不能把本地 Java/Vue 构建通过写成“镜像构建通过”。镜像构建、镜像启动、CFS 挂载以及云端验收需在具备相应条件的环境完成。

## 运行参数

敏感值只通过平台私密环境配置注入，不写入代码、镜像、构建参数、README 或截图。

| 参数 | 设置方式 |
| --- | --- |
| `SPRING_PROFILES_ACTIVE` | 固定 `prod`，入口脚本拒绝 local/test |
| `SERVER_ADDRESS` | 入口脚本固定 `0.0.0.0` |
| `PORT` / `SERVER_PORT` | 与服务监听端口一致；优先读取平台 `PORT`，否则 `SERVER_PORT`，默认 `8080` |
| `TRAINING_MYSQL_URL` | TencentDB for MySQL 8.0 的实际内网 JDBC 地址，如 `jdbc:mysql://<TENCENTDB_PRIVATE_HOST>:3306/yongsen_training?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai`；替换主机、端口和库名，TLS 参数按实际实例配置 |
| `TRAINING_MYSQL_USER` / `TRAINING_MYSQL_PASSWORD` | TencentDB 独立生产业务账号及私密密码，按上述最小权限授权；不使用 root、初始化账号、本机或测试库 |
| `TRAINING_DATA_DIR` | 默认 `/mnt/training`，必须正好是 CFS 挂载点，不能只指向普通目录 |
| `TRAINING_MAX_UPLOAD_BYTES` | 镜像固定默认 `18874368`（18 MiB）；可降低，入口脚本拒绝高于 18 MiB，给网关限制留出余量 |
| `TRAINING_DATA_KEY` | 独立随机密钥，至少 32 字符；备份并限制访问，后续版本不能随意更换 |
| `TRAINING_ADMIN_PASSWORD` | 非本地默认值、至少 12 字符的管理员初始密码；已有账号不会因修改环境变量自动重置密码 |
| `TRAINING_PUBLIC_BASE` | 实际可访问的 HTTPS 根地址，不含 `/api/v1`；用于签发文件上传和媒体短期访问 URL |
| `TRAINING_ALLOWED_ORIGINS` | 同域部署留空；另部署后台时填写精确 HTTPS origin，逗号分隔，不允许通配符 |
| `WECHAT_APPID` / `WECHAT_APP_SECRET` | 永森油服实际小程序信息；AppSecret 只在服务端使用 |
| `WECHAT_MINIPROGRAM_STATE` | `trial` 用于体验版，正式发布改为 `formal`；按实际验收版本选择 |
| `WECHAT_CALLBACK_TOKEN` / `WECHAT_CALLBACK_AES_KEY` | 暂无通知模板时可均不填；开启消息回调时成对配置，错误或部分配置不应放行 |
| `FFPROBE_PATH` / `PDFINFO_PATH` / `PDFTOPPM_PATH` | 镜像已配置为 `/usr/bin` 下对应程序 |
| `JAVA_TOOL_OPTIONS` | 镜像默认堆上限占容器内存 60%，给媒体子进程与 JVM 其他内存留空间；不得放入业务密钥 |

入口脚本不会创建数据表或修改文件系统权限。需要先创建数据库表、完成 CFS 权限，再启动服务。容器至少应预留足够 Java 与 PDF 转图内存，初始可按 2 vCPU / 4 GiB 进行验证，最终以真实样本、并发与费用评估选择规格；这不是容量实测结论。

## 微信身份与媒体访问

现有接口保留 `wx.login` 登录码经微信官方服务换取身份，再签发本系统登录 token。**部署到云托管不等于开启了“读取任意请求头即登录”**。后端不以客户端自行传入的 `X-WX-OPENID` 作为登录凭据，也不把“免鉴权调用微信接口”理解为免除本系统员工/管理员授权。

如后续改用 `callContainer` 注入身份，必须先核实请求确经平台可信链路、验证 AppID/环境归属，确认所有公网或内网直达入口不能伪造同名身份头；在这些事实没有被验证前，继续使用现有真实登录流程。不得向客户端或日志回显完整请求头、`x-cloudbase-context`、平台凭据或环境变量。[官方运行凭据提示](https://docs.cloudbase.net/run/develop/developing-guide)

管理后台浏览器和现有 `<video>` / PDF 页图仍需要普通 HTTPS 地址。仅验证 `callContainer` JSON API 成功并不足以证明媒体可播放。最小路径是开启服务 HTTPS 入口，业务 API 继续验证 token，私有资料仅通过现有短期媒体票据访问；不得把整个 CFS 目录映射成公开静态目录。微信端还须核实实际媒体域名及 request/downloadFile 等所需合法域名配置。[公网入口与小程序私有链路](https://docs.cloudbase.net/run/deploy/service-setting)

当前 `Authorization: Bearer` 携带的是本系统 token，不是 CloudBase 身份认证 token。不要直接在同一路由叠加要求 CloudBase Access Token 的网关认证后，就认为现有后台仍能登录；若启用该能力，需另行适配两层身份与媒体访问。最小部署保留现有应用鉴权，并对配置后的实际入口逐项验证。[CloudBase HTTP 身份认证](https://docs.cloudbase.net/service/authentication)

如果环境默认域名存在生产使用、备案或访问限制，使用满足实际平台要求的自定义 HTTPS 域名。若选择独立静态后台，可部署 `web/dist` 到官方静态托管，并将构建时 `VITE_API_BASE_URL` 设为实际 API 完整地址，运行时设置精确 CORS origin。[静态网站托管](https://docs.cloudbase.net/hosting/introduce)

## 必须核实的网关限制

当前 CloudBase 云托管官方文档列出 **请求包体 20M、请求超时 60 秒**。镜像将业务单次上传限制设为 **18 MiB**（`TRAINING_MAX_UPLOAD_BYTES=18874368`），由 API 返回限额并在上传前提示；不能沿用本地默认 500 MiB 来描述云端能力。本部署资产没有实现大文件直传或分块上传，超过 18 MiB 的教材应先合理分段，或在后续完成私有对象存储直传适配后使用。慢网 60 秒上传、视频 Range/206、断点播放、票据到期重取仍要通过实际云入口验证。[官方使用限制](https://docs.cloudbase.net/run/limitation)

缺少通知模板时，先验收真实登录、人员绑定、材料准备、任务发布、视频/PDF 学习、本人确认和记录导出。通知界面必须清楚显示未配置，不能创建 mock 模板或宣称微信推送已送达。之后取得实际批准模板，再完成真实个人微信收件、点击进入正确个人任务及回执关联验收。

## 云端验收与备份

1. 核实实际环境 ID、服务名、TencentDB 实例 ID 和 MySQL 8.0 引擎、同地域同 VPC/子网、内网地址与端口、安全组来源、CFS 挂载点、HTTPS 地址、AppID 归属及账号权限。CloudBase 云托管还需实际环境允许按量计费；没有开通不能视为可用，也不要自动代用户开启付费服务。[计费与使用前提](https://docs.cloudbase.net/run/limitation)
2. 在全新的 TencentDB 生产库执行 schema，验证应用账号可完成必要业务读写、无建表和越库权限；不导入 `.local`、测试库、模拟身份、示例培训、测试通知或本地管理员密码。
3. 验证 `/health` 为 `providerMode=real`、`profile=prod`，随后实际登录管理员、读写一项已授权业务。健康页只表示进程状态，不证明数据库/微信/媒体全链路成功。
4. 用真实小程序测试员完成一次绑定→领取指定任务→实际视频/PDF 学习→本人确认→后台记录与打印；确认越权请求失败、未授权人员不能获得资料票据。
5. 上传后关掉所有业务页面，验证后台处理继续完成；服务重建后原文件、页图、任务与进度仍存在。验证临时媒体 URL 到期不能使用、新票据可续学。
6. 在 TencentDB 核实自动数据备份、日志备份、备份时间窗和保留策略，检查最近成功备份与失败告警；保存期限按客户要求和实例支持范围配置，不把平台默认值当作已批准的留存要求。数据库原始事件和业务记录、CFS 原资料及页图、密钥应分别备份并关联备份批次；自动数据库备份不包含 CFS 资料。[自动备份设置与恢复说明](https://cloud.tencent.com/document/product/236/11271)
7. 升级前暂停写入并取得同一批次的 MySQL 与 CFS 备份。在隔离恢复库及隔离资料目录演练恢复，核对材料散列、员工任务、原始学习事件、确认时间与导出记录，记录恢复结果及耗时；不得直接覆盖生产库来验收恢复。若要求按时间点恢复，还须验证所选实例规格支持的数据与日志回档能力。密钥不要放进公开备份包。[TencentDB 回档能力与限制](https://cloud.tencent.com/document/product/236/7276)

费用至少包含容器计算、数据库、CFS、网络出流量及可能的镜像/日志/备份；默认域名可用、环境已开通或存在试用额度，都不代表这些资源免费。最终费用和资源授权以实际账号确认结果为准。
