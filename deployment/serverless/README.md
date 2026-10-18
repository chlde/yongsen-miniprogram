# SCF 按量部署

更新日期：2026-10-18。本目录实现按量运行后端，使用 **Java 17 Web 镜像函数、Node.js 事件桥接函数、云 MySQL 8.0、私有 COS**；当前由 CloudBase 云托管轻量网关提供小程序 `callContainer` 和管理后台入口。资源已部署，真实管理员登录和材料上传处理通过；整体微信验收仍待环境关联和真机验证。实际资源、版本及证据见 [云部署记录](../../docs/12-cloud-deployment-status.md)。上传素材直接到 COS，业务 API 不代理视频/PDF 文件。

## 资源与运行参数

先在实际账号确定地域、VPC、子网、数据库、COS 桶和镜像仓库。此处不自动选账号、不购买套餐、不创建预置并发。首次生产建独立空库，不导入 `.local` 或测试数据。

| 资源 | 初始设置 |
| --- | --- |
| CloudBase 网关 | `yongsen-api`，Node 轻量代理与 Vue 静态页面；0.25 CPU / 0.5 GB，实例 0–1，端口 9000；无业务秘密 |
| Java API | SCF **Web 函数 / 镜像部署**，Linux amd64，Java 17；镜像入口 `/opt/yongsen/scf_bootstrap`，`0.0.0.0:9000`；内存先用 3072 MB，初始化 90 秒、执行 780 秒；初期总并发上限 4，单实例请求并发 1，无预置并发 |
| 事件桥接 | SCF **事件函数 / Nodejs20.19**，代码 ZIP 根目录有 `index.js` 和 `package.json`；执行方法 `index.main_handler`；128 MB、执行 900 秒、并发上限 1，无定时触发器、无预置并发 |
| MySQL | 同地域同 VPC 的 TDSQL-C Serverless MySQL 8.0，启用空闲自动暂停，当前 0.25–0.5 CPU / 1 GB；业务账号最小权限，应用不会自动建表 |
| COS | 同地域私有标准存储桶，关闭匿名读写并限制公开 ACL；配置浏览器直传 CORS，允许实际管理后台 origin 的 PUT/GET/HEAD 和 Content-Type/Range/**x-cos-acl** 等签名请求所需 header，暴露 ETag/Content-Length/Content-Range；不可用 `*` 代替实际 origin |
| 镜像仓库 | SCF 支持的同地域 TCR 个人版或已有企业版，使用不可变版本标签/摘要；是否有额外仓库费用以实际账号为准，不为本 MVP 自动购买企业版 |
| 入口 | 创建 SCF 函数 URL，授权类型 `NONE`（开放）；业务接口仍由本系统 Bearer、角色和任务归属鉴权。内部 worker 另用独立强随机密钥，不能拿学员 token 执行作业 |

这些是初始试运行参数，尚非压测结论。素材处理代码的协作式总预算为 300 秒，内部 worker 请求必须完整执行后返回；桥接 HTTP 超时 840 秒，留出 Web 冷启动及网络余量，事件函数总超时 900 秒，持久作业租约 20 分钟。函数 URL 与实际网络的长请求、镜像启动和最大 PDF 样本必须在云端验证；不能因为默认函数超时短就让请求先返回、后台继续处理。

官方依据：[Web 函数入口](https://cloud.tencent.com/document/product/583/56129)、[镜像函数部署](https://cloud.tencent.com/document/product/583/56052)、[函数配置与超时](https://cloud.tencent.com/document/product/583/19805)、[Node.js 20.19](https://cloud.tencent.com/document/product/583/11060)、[函数 URL 创建](https://cloud.tencent.com/document/product/583/100227)。

Java 函数需要同时访问 VPC MySQL 和微信公网 API；按 SCF 网络配置开启 VPC 与公网访问，安全组只开放必要数据库来源。并发 4 不等于最多 4 条数据库连接：启动、并行请求和业务事务都需要实测。`serverless` profile 使用 `DriverManagerDataSource`，连接在请求或事务使用完后物理关闭；不依赖 Hikari 的定时空闲淘汰，因为 SCF 冻结实例时淘汰线程可能不能运行。保留连接会阻止 MySQL 暂停。首次上线后从数据库监控验证空闲连接归零及暂停，而非仅检查配置。[SCF 网络](https://cloud.tencent.com/document/product/583/123457)、[MySQL 自动暂停条件](https://cloud.tencent.com/document/product/1003/115618)。

临时磁盘仅用于单次处理，不能作为教材唯一副本。配置至少 2 GiB 临时空间并用真实素材验证余量；SCF 实际可配置容量、定价和账号配额须在创建时核实。视频、PDF 和页图的有效副本在 COS，处理失败会留下可恢复作业。未完成材料不得发布。为 `uploads/` 临时直传前缀配置 1 天过期生命周期，不得将同一过期规则应用于 `materials/` 已发布教材。不要挂 CFS 或自动添加轮询任务。

直传签名包含 `x-cos-acl: default`，浏览器必须原样发送接口返回的 `uploadHeaders`，使对象继承私有桶权限。业务 CAM 无需 `PutObjectACL` 权限；禁止为解决上传错误而放开匿名访问或授予公共 ACL 修改权限。

## 构建与打包

在仓库根执行：

```sh
npm run verify
node --test deployment/serverless/tests/*.test.cjs
node scripts/package-serverless.mjs
```

打包脚本只读取白名单源码，排除本地数据、测试资料、私密配置、证书和符号链接，扫描疑似字面量凭据，再核对归档条目与逐文件 SHA-256。输出到 `.local/serverless-artifacts/<时间戳>/`：

- `yongsen-training-serverless-source.tar.gz`：源码构建上下文，包含 `manifest.json` 中列出的文件。
- `event-bridge.zip`：可上传的 Node.js 事件桥接代码包，只有 `index.js` 与 `package.json`，不需要安装第三方依赖。
- `manifest.json`、`SHA256SUMS`：版本和归档核对材料。

源码包需要在 Linux amd64 Docker/受控 CI 环境构建镜像。解包后的根目录是构建上下文；Dockerfile 专用忽略文件已限制输入范围：

```sh
docker build --platform linux/amd64 -f deployment/serverless/Dockerfile -t "$TRAINING_SCF_IMAGE" .
docker push "$TRAINING_SCF_IMAGE"
```

`TRAINING_SCF_IMAGE` 仅填实际镜像地址，仓库凭据通过 Docker 的私密登录流程配置，不能写入源码或构建参数。部署保留镜像默认 ENTRYPOINT，CMD 留空；不能用旧 `deployment/entrypoint.sh`、8080 端口或 prod/CFS 设置。构建不传入 AppSecret、MySQL 密码或业务 CAM 密钥。

当前已通过获授权的官方 CloudBase CLI / MCP 上传白名单源码、完成云端 Linux 镜像构建并发布。运行参数检查可使用 `scf_bootstrap --check`，它仅检查已有环境变量格式，不执行网络调用、不打印值。不要在 shell 命令历史中粘贴真实密码。

## CloudBase 网关入口

同一镜像包含 Java API、Node.js 和已构建的 Web 页面。SCF 保留默认 Java 入口；CloudBase 云托管覆盖为 `EntryPoint=["/usr/local/bin/node"]`、`Cmd=["/opt/yongsen/cloudbase-gateway.cjs"]`，`TRAINING_UPSTREAM_BASE` 指向实际 SCF HTTPS origin。启用 PUBLIC / MINIAPP，网关不连接数据库、不持有微信或云账号秘密。

网关仅允许业务 API、微信回调及健康接口，屏蔽 `/api/v1/local` 和 `/internal`；不转发 `X-WX-*`、worker 密钥或外部代理身份头。请求最多 19 MiB，55 秒截止；COS 直传及长任务不经过该入口。静态文件限制在 `/opt/yongsen/web`，路径穿越、符号链接越界和双重编码均被拒绝。相关测试在 `tests/cloudbase-gateway.test.cjs`。

当前网关最多建立 3 条到 SCF 的并行连接，突发请求进入有界队列，总在途上限 32，排队时间计入 55 秒截止时间；客户端断开后取消尚未发出的请求。这样在云托管最大实例 1 的前提下，为并发上限 4 的 Java SCF 留出一个 worker 槽位，避免首页 5 个同时请求触发 432。队列满返回 503 和中文繁忙提示；不自动重试写入，不缓存或合并身份请求。若未来提高网关实例数，必须同步重新核算总并发，不能只扩大前端流量。

## 私密运行配置

Java 函数必须使用 `SPRING_PROFILES_ACTIVE=serverless`，不能与 `local`/`test`/`prod` 混用。bootstrap 固定真实微信、COS 存储、事件执行、9000 端口及 SQL 自动初始化关闭，缺少必需值拒绝启动。

| 参数 | 配置含义 |
| --- | --- |
| `TRAINING_MYSQL_URL` | 实际云库内网 MySQL JDBC URL，禁止 loopback；建议设置 `connectTimeout=10000`、`socketTimeout=30000`，TLS 按实际实例要求配置 |
| `TRAINING_MYSQL_USER`、`TRAINING_MYSQL_PASSWORD` | 专用生产业务账号；至少按业务表授予 SELECT/INSERT/UPDATE/DELETE，不用 root 或初始化账号 |
| `WECHAT_APPID`、`WECHAT_APP_SECRET` | 实际小程序公开 ID 和服务端私密凭据；永森油服 AppID 已在原生工程配置 |
| `TRAINING_ADMIN_PASSWORD` | 首次管理员密码，至少 12 字符，不能用本地默认值；已有账号不靠修改环境变量重置 |
| `TRAINING_DATA_KEY` | 独立至少 32 字符的数据加密密钥，受控备份，后续发布保持一致 |
| `TRAINING_PUBLIC_BASE` | API/管理后台 HTTPS origin，无 `/api/v1`、查询串或凭据 |
| `TRAINING_COS_BUCKET`、`TRAINING_COS_REGION` | 已创建的私有桶名（含 APPID 后缀）和实际地域 |
| `TRAINING_SCF_REGION`、`TRAINING_SCF_WORKER_FUNCTION` | 同地域事件桥接函数名，后台持久作业通过 SCF Invoke 的 Event 模式触发 |
| `TRAINING_SCF_WORKER_NAMESPACE` | 命名空间，默认 `default`；实际函数不是该空间时必须显式设置 |
| `TRAINING_WORKER_SECRET` | Java 与事件桥接函数相同的独立随机密钥：至少 32 随机字节编码为 base64url，43–128 字符，不复用管理员密码或 AppSecret |
| `TRAINING_TENCENT_SECRET_ID`、`TRAINING_TENCENT_SECRET_KEY` | **专用 CAM 业务子账号**的私密配置，仅赋予指定 COS 桶/前缀读写与 SCF Invoke 操作权限；官方 Invoke 仅支持操作级，当前无法限定单个函数，不含函数管理或 CAM；禁止用主账号或资源管理账号密钥 |
| `TRAINING_TENCENT_SESSION_TOKEN` | 可选短期 CAM 会话 token；使用临时凭据时必须由外部安全配置系统按期轮换，不能把一次性 token 当永久设置 |
| `WECHAT_CALLBACK_TOKEN`、`WECHAT_CALLBACK_AES_KEY` | 配置微信通知回调时成对注入；没有模板不能据此宣称通知已开通 |
| `WECHAT_MINIPROGRAM_STATE` | 体验验收 `trial`，正式发布按实际版本设置 `formal` |

事件桥接函数只需要 `TRAINING_WORKER_BASE_URL`（固定 Java 函数 HTTPS origin）与同一份 `TRAINING_WORKER_SECRET`。事件数据严格为 `{"jobId":"数据库持久作业的64位小写十六进制ID"}`；事件不能提供 URL、密钥或任意业务 payload。桥接对固定路径 POST 并等待结果，拒绝跳转，不打印请求/响应正文。`done`/终态 `failed` 返回确认，`busy`/`retry` 或网络不明抛异常，交给平台有限重试。最终失败保留数据库记录，由管理员受权恢复；无周期轮询。微信结果不明仍保留 UNKNOWN，不以事件重试为由重复发消息。

平台 Node.js 20 的 `context.getRemainingTimeInMillis()` 实测可能抛出 `client.ms_elapsed is not a function`。桥接捕获该辅助方法异常后使用固定 840 秒 HTTP 截止时间；方法返回非有限值则拒绝执行。它不会提前确认作业，数据库终态和租约仍是完成依据。此问题已有回归测试及真实 Event 自动处理 PDF 的证据。

**镜像凭据边界：** 官方说明镜像函数的角色临时凭据出现在平台请求头，非镜像函数才自动使用标准凭据环境变量。本实现选用上述明确的私密 CAM 变量，不信任外部请求传入的 `X-SCF-*` 凭据头，也不假定镜像能自动获得环境密钥。平台保留前缀不作为自定义配置键。[官方角色凭据规则](https://cloud.tencent.com/document/product/583/47933)。运行密钥只用于后台访问，不能打入小程序、镜像或源码归档。

## 创建顺序和域名

1. 确认腾讯云账号、地域、VPC/子网与配额。建 Serverless MySQL 8.0 空生产库，初始化账号执行 `backend/schema-mysql.sql`；已有库先备份并按新增表/字段迁移，不能直接导入本地库覆盖生产。
2. 创建私有 COS 与实际后台 origin 的 CORS，准备最小权限业务 CAM 密钥、数据密钥及 worker 密钥。创建事件桥接函数但暂不接业务流量；事件函数不设定时触发器。配置有限错误重试，并确认最终失败查询及管理员恢复入口。
3. 构建并推送版本化 Java 镜像，创建 Web 函数，设置 VPC、公网、资源限额、私密变量和 `serverless` profile。创建函数 URL 后把实际 origin 写入 Java `TRAINING_PUBLIC_BASE` 与事件函数 `TRAINING_WORKER_BASE_URL`，更新配置后完成冷启动健康检查。
4. 无自有域名的当前方案使用 CloudBase 默认 HTTPS 入口展示管理后台，并代理到 SCF。Java `TRAINING_PUBLIC_BASE` 使用管理后台 origin，worker 使用 SCF origin。分别验证静态页面、健康、管理员登录和 COS CORS；不要为解决域名问题关闭证书或应用鉴权。
5. 关联小程序与实际腾讯云环境，执行 `npm --prefix miniprogram run build:production -- --transport cloud --env "$WECHAT_CLOUD_ENV" --service "$WECHAT_CLOUD_SERVICE" --public-https "$TRAINING_PUBLIC_HTTPS"`。当前具体值见云部署记录。保持合法域名检查；COS 媒体/下载域名另外核验。备用 HTTPS transport 仅在已配置微信合法域名时使用。
6. 配置真实培训订阅消息模板及字段映射。使用个人微信完成接收任务、视频/PDF 学习、本人确认、后台记录和导出验收；核对消息最终状态和任务跳转。无模板/未实际送达不能算通知 MVP 完成。

函数 URL 自定义域名和 URL 收费规则见 [自定义域名](https://cloud.tencent.cn/document/product/583/110861)、[函数 URL 费用](https://cloud.tencent.com/document/product/583/107631)。本方案使用函数 URL，未自动添加独立 API 网关、CDN、常驻 CFS 或其他套餐。

## 上线验证边界

本地新增检查覆盖桥接完成/失败/重试、输入与密钥隔离、生产入口拒绝规则、HTTPS 小程序打包，以及源码/事件 ZIP 的归档核对。后端、COS 替身、MySQL 和完整本地 E2E 由项目验证流程记录。当前云端另已验证权限、管理员登录、COS CORS/Range、短样本材料处理及异步恢复，详见部署记录；这些结果仍不能代替暂停唤醒、最大素材与真实微信消息验收。

云端必须补齐：镜像启动及冷启动时延；MySQL 事务/断连重连/空闲暂停；COS 直传真实性、短签名刷新和 Range/206；多次并发事件幂等、超时租约与管理恢复；学习 ACK 超过 10 秒会按已有规则暂停而不会编造学习时长；真实微信消息送达；数据库备份与 COS 原件恢复。不要在上述检查通过之前标记整体开发部署任务结束。
