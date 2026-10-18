# 运行 API 契约

后端 Java17 / Spring Boot 3.5.16，`cd backend && JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home mvn spring-boot:run`。默认监听127.0.0.1:8080、local profile。`TRAINING_DATA_DIR`可指定独立绝对目录（默认进程工作目录下`./data`），文件H2为`<目录>/training`、私有文件为`<目录>/files`；不连接已有业务数据库。`SERVER_PORT`覆盖端口，`SPRING_PROFILES_ACTIVE=local`用于本地演示和验收。本地管理员 `admin / LocalTrain123!`（仅local默认，可环境覆盖）。默认微信mock只在local/test可用，真实微信需配置。`GET /health`不鉴权，返回`{status:"UP",providerMode,profile}`。生产profile启动条件与密钥见部署文档，mock/local路由不会在生产注册。

所有JSON成功 `{code:"OK",requestId,serverTime,data}`；业务错误HTTP400/401/403/409/422/429+`{code,message,requestId,details}`；冲突刷新状态，429等待后重试，未知通知不能按普通网络错误重发。列表data为`{items:[],nextCursor:null|最后一条id}`，传`?cursor=<nextCursor>`顺序取下一页。除登录/临时媒体URL/微信回调外头`Authorization: Bearer <token>`。写操作可带Idempotency-Key；发布/确认/催办必须带，浏览器生成UUID。API前缀`/api/v1`，列表每页100条，直到nextCursor为null；人员选择须取完分页。

## 身份与人员
- POST `/admin/auth/login` `{username,password}` → `{accessToken,expiresAt,role:"ADMIN",user:{id,username}}`；POST `/admin/auth/logout` → `{loggedOut:true}`。
- POST `/auth/wechat-login` `{code}` → `{accessToken,expiresAt,role:"LEARNER",bindingStatus:"UNBOUND|PENDING|ACTIVE",person:null|{id,name,...}}`。local code使用`mock:alice`，不同名称对应不同本地微信；production不支持mock。
- GET `/me` → `{role,bindingStatus,person:null|person,providerMode:"mock|real"}`。
- GET/POST `/admin/people`；POST body `{personNo,name,unit,project}` → person `{id,personNo,name,unit,project,status:"ACTIVE",rowVersion}`。
- PATCH `/admin/people/{id}` `{name?,unit?,project?,status?,expectedRevision?}`。
- POST `/admin/people/{id}/invitation` `{}` → `{inviteCode,expiresAt,personId}`。
- POST `/me/binding-requests` `{inviteCode}` → `{id,status:"PENDING",personId}`。
- GET `/admin/bindings` → items `{id,personId,personName,status,...}`；POST `/admin/bindings/{id}/decision` `{decision:"APPROVE|REJECT",reason}`；POST `.../revoke` `{reason}`。

## 管理员首次使用教程

- GET `/admin/onboarding` → `{tutorialId:"admin-basics-v1",status:"NOT_STARTED|DISMISSED|COMPLETED",updatedAt:null|ISO时间}`。首次读取无记录时返回 `NOT_STARTED`，读取本身不写库。
- PUT `/admin/onboarding` `{tutorialId:"admin-basics-v1",status:"DISMISSED|COMPLETED"}` → 同上。跳过或完成后，刷新、重新登录、换浏览器均读取同一账号的服务端状态；重播不重置历史状态。
- 仅管理员可访问，账号取当前登录身份，不接受客户端指定其他管理员。重复写幂等，`COMPLETED` 不降级为 `DISMISSED`；错误版本/状态返回400。
- 状态复用 `app_config` 的独立账号/教程命名空间，无需建表或迁移；与通知配置、员工学习进度相互独立。请求失败时前端应允许继续使用业务页面，并说明未保存，不能假报完成。

## 材料与任务
- POST `/admin/materials/uploads` `{title,type:"MP4|PDF",size,filename}` → `{id,materialId,uploadUrl,uploadMethod:"PUT",uploadHeaders,expiresAt,status:"UPLOADING"}`。
- 按 `uploadMethod` 和 `uploadHeaders` 将原始 `File` 字节上传至 `uploadUrl`（不带 JSON，不附本系统 Bearer）。本地使用短期专用票据；COS 模式使用 15 分钟有效的直传签名，绑定文件长度、Content-Type 和 `x-cos-acl: default`。浏览器自行生成 Content-Length，不手工设置。POST `/admin/materials/{id}/upload-complete` `{}` → material（PROCESSING/READY/REJECTED）。COS 完成接口校验大小、按 ETag 条件复制到新的内部对象，再登记持久作业；格式和 SHA256 经处理校验后才 READY。GET `/admin/materials`与`/{id}`查询。material `{id,title,type,status,sha256,size,durationMs,pageCount,pages:[{pageNo,width,height}],compatibilityChecked,rowVersion}`。
- POST `/admin/materials/{id}/media-ticket` `{pageNo?}` → `{url,expiresAt}`（管理员预览）；POST `.../compatibility-check` `{passed:true}`。
- POST `/admin/tasks` `{title,description,dueAt,personIds:[...],materials:[{materialId,rule:{playbackRate:1|minPageVisibleMs:30000}}]}` → task `{id,title,status:"DRAFT",...}`；PATCH `/{id}`编辑草稿。
- POST `/admin/tasks/{id}/publish` `{}` → `{id,taskId,status:"ACTIVE",assignmentCount,notificationCount}`；POST `/cancel` `{reason}`。
- GET `/admin/tasks`、`/{id}`、`/{id}/assignments`；GET `/me/assignments`、`/{id}`。
- assignment详情 `{id,taskId,personId,personName,title,dueAt,status:"NOT_STARTED|IN_PROGRESS|READY_TO_CONFIRM|COMPLETED",taskStatus,materials:[{id,taskMaterialId,materialId,title,type,durationMs,pageCount,pages,rule,progress:{effectiveMs,coverageRatio,requirementsMet,pageProgress,missingRangesMs}}],confirmationText,confirmationTextVersion,progressDigest,requirementsMet,...}`。
- POST `/me/assignments/{id}/media-ticket` `{taskMaterialId,pageNo?}` → `{url,expiresAt}`。本地媒体 URL 使用 `TRAINING_PUBLIC_BASE`；COS 模式返回对象短期签名 URL。均需先通过当前任务归属与材料权限校验，票据 15 分钟有效。视频支持 HTTP Range，PDF 页图逐页申请。响应不单独返回磁盘路径、存储凭据或内部处理令牌；签名 URL 属于临时访问凭据，不写入业务日志。
- POST `/me/assignments/{id}/entered` `{notificationId?}`。

## 学习与确认
- POST `/learning/sessions` `{assignmentId,taskMaterialId}` → `{sessionId,expiresAt,sessionState:"ACTIVE",nextSeq:1,confirmedProgress}`。已有其他活跃会话409 SESSION_IN_USE，details包含sessionId。
- POST `/learning/sessions/{id}/takeover` `{assignmentId,taskMaterialId,expectedSessionId:id}` →新会话；POST `/{id}/close` `{reason}`。
- POST `/learning/sessions/{id}/events:batch` `{batchId,clientVersion,events:[{eventId?,seq,type,elapsedMs,payload}]}`。sessionId在URL；seq从1递增、elapsedMs是相对单调毫秒。
- VIDEO_SAMPLE完整payload `{positionMs,playing,playbackRate:1,buffering,appVisible,pageVisible,playerVisible:true}`。PLAY/PAUSE/WAITING/ENDED/SEEK/HIDE/SHOW/CLOSE也带当前完整payload，状态变化立即发送。
- DOC_SAMPLE payload `{pageNo,rendered:true,visibleTopPx,visibleBottomPx,appVisible,pageVisible,readerUnobscured:true}`。支持renderReady别名；坐标页图原始像素。PAGE_ENTER/PAGE_LEAVE/HIDE/SHOW也带完整payload。
- response `{sessionId,sessionState,sessionValid,expiresAt,receivedSeq,receivedThroughSeq,verifiedSeq,missingSeqRanges,confirmedProgress:{effectiveMs,coverageRatio,requirementsMet,pageProgress,missingRangesMs},eventResults:[{seq,accepted,counted,reason}]}`。首次样本只作基线；后续真实等待1秒的连续样本核算。无需grant/epoch。断网10秒客户端应暂停，服务端30秒过期，15秒接收断档不跨段计入。缺号需重传，不能仅凭HTTP200算有效。
- GET `/me/assignments/{id}/progress` → assignment详情（含所有进度与progressDigest）。
- POST `/me/assignments/{id}/confirm` `{progressDigest,confirmationTextVersion:"mvp-1",accepted:true}` → `{status:"COMPLETED",confirmedAt,completedAt}`；不满足则422。

## 通知、配置、导出
- GET `/me/notification-settings` → `{templateId,providerMode,preference}`；POST `/me/subscriptions` `{}` → `{id,requestId,nonce,templateId,expiresAt}`；POST `/{id}/result` `{nonce,result:"accept|reject|ban|filter"}`。
- GET/PUT `/admin/notification-config`；PUT `{templateId,fieldMapping:{title:"thing1",dueAt:"time2"},expectedRevision?}`。mock默认本地模板。POST `/check`返回健康。
- POST `/admin/tasks/{id}/reminders` `{personIds,reason,newSubscriptionId?,confirmPossibleDuplicate?}` → `{notifications:[],queuedCount,skippedCount}`。
- GET `/admin/tasks/{id}/notifications` → items；GET `/admin/notifications/{id}` → `{...notification,attempts:[]}`。通知字段 `{id,assignmentId,personId,sendStatus,deliveryStatus,openedAt,providerMode}`。
- local专用 GET `/local/outbox`（管理员）→ items真实持久化mock卡片 `{id,notificationId,assignmentId,title,providerMsgId,deepLink,providerMode:"mock"}`。POST `/local/notifications/{id}/deliver`（管理员） `{result:"DELIVERED|DELIVERY_FAILED"}` 走模拟回调处理。POST `/local/notifications/{id}/simulate` `{outcome:"UNKNOWN|FAILED"}`仅local故障测试；生产不存在上述入口。
- GET `/admin/tasks/{id}/records.csv` 返回UTF8 BOM CSV；GET `/admin/assignments/{id}/print`或`/me/assignments/{id}/print`返回HTML（fetch带Bearer后本地展示/打印）。
- GET/POST `/integrations/wechat/yongsen/callback`在根路径，微信独立验签加密协议。

## 云端后台处理与恢复

- GET `/admin/background-work?limit=100&offset=0` → `{items:[{id,kind,targetId,status,attempts,errorCode,updatedMs,leaseUntilMs}],hasMore}`。未完成作业优先，然后按更新时间倒序、ID 排序；通过 offset 翻页，刷新回到首页。`kind` 为 `MEDIA` 或 `NOTIFICATIONS`；`status` 为 `QUEUED/DISPATCHED/RUNNING/RETRY/FAILED/DONE`。
- POST `/admin/background-work/{id}/retry` `{}` → `{queued:true}`。仅管理员；DONE 和有效租约中的 RUNNING 不可重试。返回值表示已请求恢复，不表示处理成功或微信送达；需查询最终状态。操作记入审计。
- 内部 POST `/internal/background-work/{id}/run` 不在 `/api/v1` 下，只接受独立 `TRAINING_WORKER_SECRET` Bearer，学员和管理员 token 均无效。成功返回原始 `{status:"done|busy|retry|failed"}`，供 SCF 事件桥接使用；终态失败保留人工恢复，执行中或可重试失败触发平台有限重试。
- `serverless` 关闭调度轮询；作业与业务变更在同一事务持久化，提交后派发事件。事件重复、过期 worker 和未知发送结果不能重复制造通知或学习完成。SCF 接受事件只标记 DISPATCHED，作业 DONE 也不代表通知已送达。微信通知仍以原有发送、回执和打开状态分别记录。

输入边界：任务标题最多200字符、说明最多10000字符；每任务1～500人、1～10项材料；人物编号唯一。发布、催办、确认的幂等键最多128字符；催办与确认同键异内容返回409。CSV包含人员快照、材料SHA256、学习覆盖与有效毫秒、通知发送/送达/打开记录和导出时间。UNKNOWN再次提醒只需管理员勾选可能重复确认：服务端从该学员当前绑定和当前模板查找发送后新取得且尚未使用的accept线索，缺少则拒绝；`newSubscriptionId`可选用于精确指定，不要求学员传内部ID。
