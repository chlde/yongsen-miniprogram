# MVP 数据模型与接口契约

版本 1.0，2026年10月17日。产品边界见[产品与架构](01-product-and-architecture.md)，学习计时和微信处理规则见[学习与推送](02-learning-and-notification.md)。本文保留逻辑设计结构，并已按当前源码校正；实际可调用字段、路径及返回结构以 [运行契约](runtime-api.md) 为准。下文数据库字段用snake_case，API使用camelCase；标为补充JSON的字段存于实体的snapshot_json，不是独立SQL列。本地实现已建表，尚未上线。

实现使用 Java 17 / Spring Boot 单体服务，默认文件 H2 方便本地运行；生产使用 MySQL 8。实际字段及约束以 `backend/src/main/resources/schema.sql` 和 `backend/schema-mysql.sql` 为准。十六张业务表之外有登录会话、临时媒体票据、本地模拟收件箱三张支持表；仓储查询使用具名DO，服务与API边界使用JSON映射，结构化快照和原始事件保留JSON。生产不会开放本地模拟收件箱接口。

## 1 开发范围与统一约定

首期只有管理员、学员两个角色。管理员建人员、核对微信绑定、上传可用的 MP4/PDF、向指定人员立即发布任务、人工催办和导出记录。学员完成学习后本人确认。不建设课程层、定时发布、自动提醒、考试、现场考核、多级复核、外部审核门户或独立存证平台。

- 服务端生成不透明字符串 ID；OpenID、微信 MsgID 全部按字符串处理。时间使用 UTC 存储，界面按北京时间显示；会话、邀请码和票据有效期以服务端时间为准。dueAt用于截止提示，不自动取消任务或阻止逾期补学。
- 普通字段包含 `id,created_at,updated_at,row_version`；文件、原始事件载荷和完成快照不覆盖，处理状态与进度投影可更新。手机号不是首期必填字段，也不调用短信平台。
- 人员有效性、当前绑定和任务归属由服务端逐次检查，不能依赖前端隐藏入口、不可猜测 ID 或通知链接。管理员管理本企业数据，学员只能操作本人任务。
- API 前缀为 `/api/v1`。成功返回 `{code:"OK",requestId,serverTime,data}`，错误返回 `{code,message,requestId,details}` 并使用适当 HTTP 状态。
- 分页采用 `items,nextCursor`，实现固定每页100条，前端按游标继续读取。编辑请求可带 `expectedRevision`，提供时检查版本，冲突返回409。缺少必填字段返回400；业务条件不满足返回422；身份失效返回401；无权限返回403。通知处理接口的失败不能改变学习状态。
- 发布、人工催办、确认等写请求带 `Idempotency-Key`。催办、确认使用规范化请求摘要，同键异内容返回409；发布以任务和publishKey保证不重复分配，已发布任务不接受另一个发布键。业务键保存在对应实体，催办操作记录存任务补充JSON，不单建通用幂等平台。事件采用会话序号去重。
- 管理员使用Spring Security BCrypt密码哈希、8小时不透明Bearer会话和登录限流；令牌仅存哈希，不使用自动附带的登录Cookie。浏览器显式发送Authorization并配置精确CORS来源；未实现独立的CSRF令牌流程，可部署在受控入口。学员用微信登录后的业务会话，AppSecret、微信 access_token 不下发。注销与撤销绑定标记相关登录令牌失效；人员停用由每次业务鉴权拒绝操作。不自行实现密码学。

## 2 十六张核心逻辑表

以下列出当前核心持久字段和业务约束；完整物理结构以两个schema文件及`RecordColumns`为准。每表还有通用`ref_id/ref2/record_status/unique_key/snapshot_json`字段，用于关系索引、状态筛选、业务唯一键和少量补充元数据。文件当前保存在`TRAINING_DATA_DIR/files`私有目录，数据库保存受控相对文件键，API不暴露磁盘路径或永久公开URL。CSV和打印HTML按请求生成，不写入对象存储；首期未接入云对象存储。

| 表 | 当前关键字段 | 约束与用途 |
| --- | --- | --- |
| `admin_user` | username,password_hash,status | unique_key保存唯一登录名；仅管理员；BCrypt哈希 |
| `person` | person_no,name,unit,project,status,invite_hash,invite_expires_ms,invite_consumed,active_session_id,send_lease_until,active_notification_id | person_no唯一；邀请码单次展示；学习活跃指针和通知发送租约分别管理 |
| `wx_identity_binding` | person_id,person_name,identity_id,encrypted_openid,status,invite_hash,checked_by,checked_at,active_identity_key | identity_id为当前AppID与OpenID的SHA-256组合摘要；OpenID加密存储；PENDING/ACTIVE占用人员及身份唯一键，历史行释放唯一键 |
| `material` | title,type,original_key,sha256,size,duration_ms,page_count,pages,status,compatibility_checked,compatibility_checked_at,render_version | pages为固定页图清单JSON；原PDF保留；处理成功为READY，还须compatibility_checked=true才能发布 |
| `task` | title,description,due_at,status,person_ids,materials,published_at,created_by,publish_key,cancellation_reason | DRAFT/ACTIVE/CANCELLED；发布后固定名单和内容；催办幂等操作存在补充JSON；取消时间可查审计 |
| `task_material` | task_id,material_id,title,type,duration_ms,page_count,pages,material_snapshot,rule,rule_hash | unique_key为taskId:materialId；sortOrder存补充JSON，按选择顺序展示；全部必学 |
| `assignment` | task_id,person_id,person_snapshot,binding_snapshot,status,first_entered_at,first_learning_at,last_learning_at,confirmed_at,completed_at,confirmation_snapshot,confirmation_hash,confirm_key | UNIQUE(task_id,person_id)；confirmRequestHash存补充JSON；完成后快照固定 |
| `learning_session` | person_id,assignment_id,task_material_id,binding_id,state,expires_ms,started_ms,last_received_ms,received_seq,verified_seq,previous_sample,speed_window | ACTIVE/EXPIRED/CLOSED/REPLACED/REVOKED；最大收到序号、已封闭缺口、累计推进和待处理预算存补充JSON |
| `learning_event` | session_id,event_id,seq,batch_id,type,elapsed_ms,received_ms,payload,payload_hash,accepted,counted,decision,reason | UNIQUE(session_id,seq)；event_id为可选客户端追踪值，无全局唯一约束；原载荷不可覆盖，处理判定可更新 |
| `learning_progress` | assignment_id,task_material_id,covered_ranges,page_progress,effective_ms,requirements_met,coverage_ratio | UNIQUE(assignment_id,task_material_id)；只由服务端核算，前台不能写完成 |
| `notification` | assignment_id,person_id,task_id,kind,operation_key,send_status,delivery_status,opened_at,previous_notification_id,next_attempt_ms,attempt_count,started_ms | 同时作为outbox；unique_key=active:assignmentId保障一个活跃待发意图；终态释放该键；人工操作幂等在任务记录中校验 |
| `notification_attempt` | notification_id,binding_id,subscription_record_id,template_snapshot,payload_snapshot,send_status,delivery_status,send_errcode,delivery_errcode,provider_msg_id,started_ms,responded_at,callback_id | 每次发送新建UUID行，不覆盖旧尝试；没有attempt_no及其组合唯一索引；通知锁和计数用于串行创建 |
| `subscription_record` | binding_id,identity_id,template_id,nonce_hash,expires_ms,client_result,provider_callback_id,confidence,result,hint_used_by_notification_id,invalidated | id即申请requestId；允许多个未过期申请，不维护资格余额；微信回调仅在身份/模板窗口内恰有一个可匹配申请时合并 |
| `wechat_callback` | event_type,payload,provider_msg_id,matched_attempt_id,match_status,receive_count,processing_state | unique_key为事件类型与规范化载荷摘要；回调原对象直接存JSON；不能关联保留UNMATCHED，不另存原始文件 |
| `app_config` | id,template_id,field_mapping,row_version | 当前id固定notification；非密钥配置，更新可带版本，变更写审计 |
| `audit_log` | actor_id,action,target_id,occurred_at,detail | 只追加；记录核对、发布、取消、导出、事件冲突及失效批次；不记邀请码原文、密钥和登录code |

业务时间`*_at`采用DATETIME(3)，API输出UTC毫秒ISO字符串；租约和采集时间`*_ms`为毫秒整数，不能混用秒。另有`auth_token/media_ticket/mock_card`三张支持表，不属于上述十六张业务表。

绑定唯一约束由unique_key=person:personId和active_identity_key实现，仅PENDING/ACTIVE行占用；历史REJECTED/REVOKED行将这些键设为空，数据库唯一索引控制并发。材料替换必须新增 material；重新培训必须新建任务，不重置历史 assignment。

通知模板ID和字段映射由管理员通过API-22保存到app_config；记录版本并校验支持的映射字段及命名后生效，不改变已发通知快照。此校验不是微信模板资格或真实送达校验。AppSecret、回调Token和加密密钥仅在受保护部署环境维护。配置页面展示非密钥配置和接入信息，不增加微信模板申请平台。

材料处理直接复用后台作业循环：上传完成后将材料置为PROCESSING，检查真实文件格式与大小，通过ffprobe或pdfinfo/pdftoppm提取视频信息或生成PDF页图；完成后进入READY，失败为REJECTED并保留可读原因。上传地址限制对象键、有效期和大小，不能让客户端任意指定服务器文件路径。

首期限额固定为视频500MiB且60分钟以内、PDF50MiB且100页以内；当前是后端常量，不支持环境变量调整。每任务500人、10项材料同样是固定常量。MP4需要在目标手机可播放；检查不兼容时要求管理员重新提供文件，不自动转码。PDF只做阅读适配：保留原PDF，生成固定页图；pages保存每页相对文件键、宽高、页序与摘要，material保存渲染版本。加密PDF、损坏文件、漏页、页序错误或无法清晰阅读的内容不能发布。管理员用手机预览后确认，不增加审批角色。

短期媒体地址通过API-17获得，固定有效15分钟；管理员预览另用`POST /admin/materials/{id}/media-ticket`。请求必须同时核验本人assignment和task_material关系；PDF页码必须在固定清单内，不能通过传入任意objectKey访问其他文件。页面尺寸等读取规则从服务端清单取得，客户端不能用更小尺寸、伪造页数或修改视频时长降低完成要求。已发地址过期后可重新申请，但重新取得地址不会重置已确认进度。

## 3 邀请码与人工核对绑定

管理员预建人员后生成个人邀请码，采用至少128位随机的不可预测值，库中仅存安全哈希；默认有效7天。生成新码撤销旧码，下载/展示仅给授权管理员，不批量暴露给学员。邀请码只能定位一个人员，不能独立证明持有人身份。

学员先调用微信登录，服务端用 code 换得当前 AppID 下的 OpenID，建立未绑定会话。提交邀请码时，在同一事务校验并消费邀请码、创建 PENDING 绑定；界面只返回待核对状态及必要的本人信息，不开放名册搜索。管理员通过已有线下联系核对姓名、人员编号和本人微信，再批准或拒绝。

批准事务锁定人员和绑定：确认申请对应本次已消费的邀请码、未被后续重新发码撤销，保持唯一关系，将绑定设为ACTIVE并写审计；人员停用仍会被后续业务鉴权拒绝。拒绝后旧码不能再用，管理员核实后重新发码。并发提交同一码不能形成两个有效绑定。未核对、停用或被撤销的身份不能学习或被视为可微信推送。

换绑由管理员先撤销旧绑定和旧登录令牌，再发新邀请码重新核对。旧学习会话因绑定ID不匹配不能继续上报，首期不在撤销时批量改写所有学习会话状态。已发通知与已完成记录保留旧绑定ID及快照；尚未发送的通知重新检查当前绑定。无需迁移OpenID或改写原记录。

## 4 任务、学习与确认状态

任务只有草稿、有效、取消三种状态。发布前服务端返回实际人员和材料的校验错误；首期每任务最多500人、10项材料，超过需拆为独立任务。用户选择的明确 personIds 是发布名单，不在发布时重新扩展单位或项目筛选。

一次发布事务将任务改为 ACTIVE，固定 task_material 的材料及规则快照，为每人生成 assignment、学习进度初值和首发 notification。微信调用在提交之后异步进行，网络失败不回滚任务。未绑定或无可用订阅线索时保留待处理结果；绑定和订阅条件补齐后可唤醒原首次待发通知，发送前重新校验。这是在完成同一次首发，不生成定时催办。重复发布返回原任务和分配数量，不再次创建分配与通知。

个人状态使用 `NOT_STARTED → IN_PROGRESS → READY_TO_CONFIRM → COMPLETED`。READY_TO_CONFIRM 仅表示全部材料满足已固定的学习规则；本人确认后才成为 COMPLETED。声明固定为“我确认由本人完成本任务所列材料的学习，并已阅读任务要求。”，不能预勾选或由管理员代确认。取消任务使未完成任务不可继续学习，已完成事实保留，打印页同时说明任务当前已取消。

会话开始时锁定 person：无有效会话则建立；已有其他有效会话返回 SESSION_IN_USE。学员明确接管后撤销旧会话，并更新 active_session_id。所有事件同时验证人员有效、当前绑定ID、任务有效和会话ID与当前活动指针一致。身份仍合法但会话失效时，只写LEARNING_BATCH_REJECTED审计，保留sessionId、batchId、事件数、批次摘要和原因，不插入这次原始事件、不延长租约或增加进度。绑定撤销后鉴权直接拒绝，已有撤销审计保留。

视频按已覆盖区间的并集核算；PDF按固定页/区段与有效停留核算。原始事件不可覆盖，learning_progress由服务端串行更新。重复事件同序号同摘要返回DUPLICATE且不重复计时；内容不同在eventResults返回EVENT_SEQ_CONFLICT并写原摘要/新摘要审计。切后台、缓冲、跳转、缺失采样和不合理时序不直接累计完成量；02说明规则边界，当前字段与实际核算逻辑分别见运行契约和LearningService/LearningMath。

确认请求携带最新进度摘要。服务端锁定 assignment，检查任务仍有效、全部必学项达标、摘要未过期，再把本人确认文本、人员与绑定核对快照、本任务材料清单、规则和进度依据保存到confirmation_snapshot并计算摘要；确认和完成时间同时写入assignment独立字段。这里不引入课程实体。确认事务同时取消未发送通知；客户端不能提供 completed=true 来改变结果。

确认快照不因以后改名、改单位/项目、配置更新或再次打印而变化。发现记录错误需取消相关后续使用并新建任务补学；首期不提供管理员直接补满进度或修改完成时间的按钮。学习完成说明仅覆盖系统设定的学习要求，不声明已通过未实施的考试或实操考核。

## 5 微信发送的最小可靠实现

notification任务行在发布事务中创建，即为outbox，不另建消息中间件和通用事务平台。worker按状态领取，使用通知行锁和person发送租约串行发送同一人员的通知；当前单实例worker本身也不重叠。人员与微信身份采用一对一有效绑定；首期没有独立的跨部署微信身份锁服务。锁过期不证明请求没发出。

首期每次订阅只请求一个配置中的模板。用户主动点击前可预取申请nonce；调用微信订阅API必须保留真实用户点击链路。客户端结果是HINT_ONLY，验签回调是PROVIDER_OBSERVED；同一申请的两份观察更新同一subscription_record，不分别增加资格。只有accept可形成可尝试状态，不显示精确微信余额。同任务同人员已有WAITING_BINDING/WAITING_SUBSCRIPTION/READY/SENDING的未发通知时，人工催办返回该已有意图，不额外创建；在事务中检查并串行更新。

subscription_record仅记录本次申请、客户端结果、可关联的微信结果及处理时间，不建立订阅余额。申请nonce有效5分钟；未收到结果的行维持PENDING，过期提交返回NONCE_EXPIRED，可以新建申请，不会有后台任务自动改为UNKNOWN。普通nonce过期不等于微信已撤销订阅；真实发送仍由微信判断资格。无法关联的回调单独保留，不产生另一条发送线索。

发送前在事务中把本次采用的accept线索关联到notification，并创建attempt；同一条线索不能并发支持两个通知，也不因后来收到相同申请回调重复使用。当前仅明确收到微信errcode=-1系统忙拒绝时让同一notification退避重试，最多3个attempt；UNKNOWN保留关联防重。43101将该身份模板的旧线索标记失效，等待新订阅；拒收回调阻止未发送通知，迟到旧accept不覆盖更新的reject。这里的线索只是“值得尝试发送”的保守判断，不能代替微信授权。

notification和attempt保存两维；通知sendStatus为 `WAITING_BINDING/WAITING_SUBSCRIPTION/READY/SENDING/ACCEPTED/FAILED/UNKNOWN/CANCELED`，attempt只使用实际发送阶段的SENDING/ACCEPTED/FAILED/UNKNOWN。deliveryStatus为 `NOT_SENT/UNKNOWN/DELIVERED/DELIVERY_FAILED`。明确未发送时为NOT_SENT，接口已受理或结果不确定时为UNKNOWN；可靠回执确认后才改变送达结果。openedAt仅说明用户实际从该通知入口进入任务。

写出请求后的超时、连接重置、无法确定的HTTP错误或进程中断进入UNKNOWN，不自动重发，也不释放可能已使用的订阅提示。微信明确40001/42001凭证拒绝时Provider内部刷新一次凭证重试；明确errcode=-1由队列有限重试；43108及其他明确失败当前保持FAILED，需管理员核对后发起新的人工催办。API成功而最终下发失败仍保留ACCEPTED的发送事实，不自动进入普通重试器。对发送或送达UNKNOWN的旧通知，管理员须看到可能重复的提示，取得学员在该次发送之后的新订阅结果并明确确认后，才允许创建新意图。newSubscriptionId可省略，服务端按当前ACTIVE绑定、当前模板和旧发送时间筛选尚未失效/使用的新accept线索并固定给新通知；显式传ID时还会核验其条件。保留旧通知UNKNOWN，不能直接重试原记录。

回调请求先验签，密文模式解密并校验AppID；随后在同一事务中同步落库、去重和更新匹配结果，提交后返回success，当前没有另设异步回调队列。重复回调只增加接收次数。发送结果须同时匹配msgid、FromUserName与attempt对应OpenID、TemplateId；缺少可靠msgid时保存UNMATCHED，不能按同用户最近一条消息强行匹配。若回调早于发送HTTP响应，响应保存msgid后会再次尝试匹配已存回调。sendStatus=ACCEPTED且没有可关联回调时，deliveryStatus保持UNKNOWN，显示“接口已受理，送达结果未知”。只有可靠关联的微信成功/失败回执才能设置DELIVERED/DELIVERY_FAILED。微信下发成功、用户进入任务、学习完成分别展示。

订阅接口只接受当前已核对身份，不接受客户端指定OpenID。每次nonce绑定身份、模板和请求；重复提交同结果返回原记录，异结果报告冲突，保留实际微信回调作为独立证据。页面刷新、网络重试不能新增发送机会；新一轮订阅必须由用户重新主动操作。当前映射仅支持title和dueAt；title按前20字符生成卡片内容，dueAt存在时转换为北京时间。映射结构校验由服务端完成，模板实际字段类型和必填项仍需部署前核对及微信API验证，不能将配置check当成可发送证明。

人工催办接口接受明确人员列表，服务端逐人查找当前任务分配；不属于任务的人员导致整个催办事务失败，已完成者计入skippedCount，停用人员由发送前校验取消待发意图。当前不返回逐人不可发原因列表。尚未绑定或未订阅的人员显示待处理，不宣称通知已排队可送达。已发或UNKNOWN的旧意图不被覆盖；WAITING_BINDING/WAITING_SUBSCRIPTION/READY/SENDING意图复用，同一次人工操作以幂等键防重复。通知深链带notificationId和assignmentId；API-14再次校验二者关联及当前人员确为接收者，写入该notification的首次openedAt。点击只增加进入事实，不代替微信已读回执，也不更新学习量和确认状态。

## 6 接口清单

共25组接口；同组列出的多条路由分别实现。本表是功能索引；必填性及精确返回结构以运行契约为准，不能把概念字段直接当作已实现参数。响应实体省略通用ID和版本。管理员路由全部检查管理员身份，`/me`和`/learning`路由检查当前微信业务会话。仅登录、提交绑定、读取本人绑定状态允许未绑定会话。

| 编号 | 方法与路径 | 请求与关键返回 |
| --- | --- | --- |
| API-01 | POST `/admin/auth/login`；POST `/admin/auth/logout` | username,password；认证库建立/撤销安全会话；登录失败统一文案及限流 |
| API-02 | POST `/auth/wechat-login` | code；返回业务会话、expiresAt、bindingStatus；code只用于服务端换取身份 |
| API-03 | GET `/me` | 返回role、本人信息、绑定状态及providerMode；未绑定不返回其他人员数据 |
| API-04 | POST `/me/binding-requests` | inviteCode；返回id、personId和PENDING；防猜测限流，不记录原文 |
| API-05 | GET/POST `/admin/people`；PATCH `/{id}`；POST `/{id}/invitation` | 人员编号、姓名、单位、项目、状态；邀请返回一次性原文与expiresAt；编辑带版本 |
| API-06 | GET `/admin/bindings`；POST `/admin/bindings/{id}/decision`；POST `/{id}/revoke` | decision APPROVE/REJECT、reason；该接口未读取expectedRevision；撤销也需原因，原历史保留 |
| API-07 | POST `/admin/materials/uploads`；POST `/admin/materials/{id}/upload-complete` | title,type,size,filename；返回受限上传地址，由PUT上传原始字节；完成时受理后返回202及PROCESSING；同步发现文件错误可返回REJECTED；处理状态由API-08查询 |
| API-08 | GET `/admin/materials`；GET `/admin/materials/{id}`；POST `/{id}/compatibility-check` | 返回格式、sha256、时长/页数，预览需另申请media-ticket；页图生成完成后管理员手机预览确认；材料READY且compatibilityChecked=true才允许发布 |
| API-09 | POST `/admin/tasks`；PATCH `/admin/tasks/{id}` | title,personIds,materials[{materialId,rule}]；description/dueAt可选；仅草稿可编辑 |
| API-10 | POST `/admin/tasks/{id}/publish` | 请求体可为空，需Idempotency-Key；完整校验后一次事务发布；返回assignmentCount及通知排队人数，不宣称已送达 |
| API-11 | GET `/admin/tasks`；GET `/admin/tasks/{id}`；GET `/{id}/assignments` | 分页返回任务或个人分配摘要；完整材料进度用GET `/admin/assignments/{id}`，通知状态用API-21 |
| API-12 | POST `/admin/tasks/{id}/cancel` | reason；该接口未读取expectedRevision；终止未完成学习与未发通知，保留历史 |
| API-13 | GET `/me/assignments`；GET `/me/assignments/{id}` | 返回本人任务、固定材料、规则、进度、确认状态；他人任务不可读，取消任务仍可查看历史 |
| API-14 | POST `/me/assignments/{id}/entered` | notificationId可选；校验通知、任务、当前人员的关联后写openedAt；ID不授予访问权 |
| API-15 | POST `/learning/sessions`；POST `/{id}/takeover`；POST `/{id}/close` | 开始含assignmentId,taskMaterialId；接管含expectedSessionId，关闭可发空body，reason当前不落库；返回sessionId,expiresAt、序号和进度 |
| API-16 | POST `/learning/sessions/{id}/events:batch` | batchId,clientVersion,events；返回逐项结果、receivedSeq、verifiedSeq、缺号和已确认进度；细节见02 |
| API-17 | GET `/me/assignments/{id}/progress`；POST `/{id}/media-ticket` | 进度含视频missingRangesMs、PDF pageProgress和progressDigest；媒体请求含taskMaterialId，PDF另含pageNo，返回短期受限URL |
| API-18 | POST `/me/assignments/{id}/confirm` | progressDigest,confirmationTextVersion,accepted=true；服务器复核后返回COMPLETED与confirmedAt |
| API-19 | GET `/me/notification-settings`；POST `/me/subscriptions`；POST `/me/subscriptions/{id}/result` | 查询模板和提示状态；创建返回nonce/templateId；结果含nonce,result，作为客户端提示保存 |
| API-20 | POST `/admin/tasks/{id}/reminders` | personIds,reason；存在旧UNKNOWN时需confirmPossibleDuplicate=true及可用新授权；newSubscriptionId可选，服务端可自动选择；返回notifications、queuedCount、skippedCount |
| API-21 | GET `/admin/tasks/{id}/notifications`；GET `/admin/notifications/{id}` | 返回意图；详情另含历次attempt、sendStatus、deliveryStatus和首次openedAt；无msgid的回调不伪关联 |
| API-22 | GET/PUT `/admin/notification-config`；POST `/admin/notification-config/check` | PUT含templateId,fieldMapping,expectedRevision；保存非密钥映射及审计，GET返回配置、providerMode和回调路径；check只返回是否已配置，liveDeliveryVerified恒为false，不检测公网回调健康、不试发 |
| API-23 | GET `/admin/tasks/{id}/records.csv` | 下载任务记录CSV；按管理员权限过滤，导出写审计，防CSV公式注入 |
| API-24 | GET `/admin/assignments/{id}/print`；GET `/me/assignments/{id}/print` | 单人打印HTML；管理员按权限、学员仅本人；浏览器可打印存PDF，标明生成时间及记录性质 |
| API-25 | GET/POST `/integrations/wechat/yongsen/callback` | 微信专用验签、加密协议；不使用上述JSON包装或员工Bearer；应用路由不加业务API前缀 |

表中`/{id}`简写继承该组前一条集合路径；实际接口中使用完整路径；本项目未生成独立OpenAPI文件。`POST /learning/sessions/{id}/takeover`只接管该人员自己的当前会话。通用管理列表支持必要的姓名、编号、任务状态查询，不额外建设复杂报表引擎。

## 7 核心请求与响应样例

示例ID为便于阅读的占位字符串。创建任务时仅允许以下已支持规则；视频1倍速和完整覆盖固定，PDF停留由管理员确认，其他内部参数由服务端按02固定到规则快照。personIds直接保存到草稿，发布时不重新扩展组织筛选。

```json
{"title":"作业安全学习","description":"学习视频及作业须知","dueAt":"2026-10-20T10:00:00Z","personIds":["person-01"],"materials":[{"materialId":"mp4-01","rule":{"playbackRate":1}},{"materialId":"pdf-01","rule":{"minPageVisibleMs":30000}}]}
```

API-16视频批次：会话身份以URL的sessionId为准，事件体不传sessionId；seq为正整数，elapsedMs和positionMs均为非负整数毫秒，倍速为数值，状态为布尔值。下例是开始后立即上报的完整基线，不能凭第一条记录累计时长。

```json
{"batchId":"batch-video-01","clientVersion":"1.0.0","events":[{"eventId":"event-video-01","seq":1,"type":"VIDEO_SAMPLE","elapsedMs":0,"payload":{"positionMs":0,"playing":true,"playbackRate":1,"buffering":false,"appVisible":true,"pageVisible":true}}]}
```

同一接口的PDF批次使用独立文档会话。pageNo从1开始；可见区间使用固定页图原始像素坐标，不能使用手机屏幕坐标。必须满足0≤visibleTopPx<visibleBottomPx≤该页原始高度；服务端按固定页图高度校验可见区间；当前不验证手机实际布局或页宽，前端负责按原图尺寸换算并采集遮挡状态。

```json
{"batchId":"batch-pdf-01","clientVersion":"1.0.0","events":[{"eventId":"event-pdf-01","seq":1,"type":"DOC_SAMPLE","elapsedMs":0,"payload":{"pageNo":1,"rendered":true,"visibleTopPx":0,"visibleBottomPx":900,"appVisible":true,"pageVisible":true,"readerUnobscured":true}}]}
```

标准响应示意视频首条基线已接收但未增加时长。receivedSeq是已收到的最大连续序号，verifiedSeq是已完成判定的处理位置，缺号超时封闭后可跨过缺号；closedGapRanges给出封闭范围，两者不保证相等。缺号范围使用包含首尾的整数对；视频缺失位置使用毫秒区间。客户端只有看到confirmedProgress更新，才能增加“已确认进度”；超时需按02暂停。

```json
{"code":"OK","requestId":"request-01","serverTime":"2026-10-16T02:00:00Z","data":{"sessionId":"session-video-01","sessionValid":true,"expiresAt":"2026-10-16T02:00:30Z","receivedSeq":1,"verifiedSeq":1,"missingSeqRanges":[],"eventResults":[{"seq":1,"accepted":true,"counted":false,"reason":"BASELINE_OR_INVALID"}],"confirmedProgress":{"effectiveMs":0,"coverageRatio":0,"requirementsMet":false,"missingRangesMs":[[0,600000]]}}}
```

事件不含客户端完成状态、人员身份或可信墙上时钟。重传保持相同seq及原载荷，eventId可选但传入后也属于摘要内容。重复/冲突和计时判断通过eventResults逐项说明；超出批次限额、非法seq/elapsedMs或不支持的事件类型会使该批事务整体拒绝，不保证所有格式错误都部分成功。相同序号不同内容不覆盖先前事实。

## 8 导出内容与关键事务

CSV每个个人任务一行，至少包含人员编号、姓名与发布时单位/项目、任务名称、发布时间、截止时间、材料名称/版本摘要、学习状态、视频覆盖或文档完成摘要、有效学习时长、确认时间、通知状态与进入时间。以字段说明解释计时口径，不把有效秒数直接宣称为法定学时。含`= + - @`等风险前缀的自由文本按安全文本导出。

单人打印页包含人员与微信绑定核对快照、固定任务材料、规则、逐项进度、开始和最后学习时间、本人确认原文与确认/完成时间、通知与取消摘要、文件摘要、记录编号和生成时间。已完成部分使用不可变确认快照；其后发生的通知回调和任务取消显示为当前通知状态及任务状态；页面标记生成时间，首期不展示每个状态的完整变更时间线。页面说明仅证明系统记录，不增设未经实施的考试成绩或电子签名法律效力。首期不自动生成PDF或ZIP，管理员使用浏览器打印保存。

| 操作 | 事务及失败语义 |
| --- | --- |
| 核对绑定 | 锁人员与申请、核验本次已消费邀请码及唯一关系、启用绑定、审计；失败全部回滚 |
| 发布 | 固定材料/规则/人员快照、ACTIVE、assignment/progress、notification；唯一键防重复 |
| 学习采集 | 原事件与序号校验落库；同会话串行更新进度，客户端只显示服务端已确认值 |
| 本人确认 | 锁assignment、重查要求、存确认快照和确认/完成时间、COMPLETED、取消未发通知；重复返回原结果 |
| 微信发送 | 领取notification、关联订阅线索并落attempt；提交后调用微信；结果不明保留UNKNOWN |
| 取消任务 | 更新任务、撤销活跃会话、取消未发通知、审计；已经在途消息只记录后续结果 |

实际常见错误包括`BINDING_REQUIRED,PERSON_INACTIVE,INVITE_INVALID,VERSION_CONFLICT,IDEMPOTENCY_CONFLICT,TASK_NOT_ACTIVE,MATERIAL_NOT_READY,SESSION_IN_USE,LEARNING_NOT_COMPLETE,PROGRESS_CHANGED,NONCE_EXPIRED,UNKNOWN_REQUIRES_CONFIRMATION,NEW_SUBSCRIPTION_REQUIRED`。邀请码无效、过期或已消费统一INVITE_INVALID。会话失效可用HTTP200返回`sessionValid=false`及具体sessionState；EVENT_SEQ_CONFLICT是eventResults逐项结果，不一定是HTTP错误。前端须同时处理HTTP状态、业务码和采集响应，不凭HTTP200增加进度。

部署要求是对数据库和私有文件一起备份并做恢复演练。当前应用不提供自动备份、自动归档或按保存期限清理任务；保存期限及清理流程由企业另行确定，清理不得先删除仍被确认快照引用的证据。上线先检查账号和模板、真实手机学习、一次真实首发及人工催办、真实微信收卡片及点击任务、未知发送防重和一份可读的CSV/打印页，验收详见[实施与验收](04-delivery-and-acceptance.md)。
