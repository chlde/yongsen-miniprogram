package com.yongsen.training;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/api/v1")
public class ApiController {
    private static final Pattern TEMPLATE_ID = Pattern.compile("[A-Za-z0-9_-]{1,128}");
    private static final Pattern TITLE_FIELD = Pattern.compile("thing[1-9][0-9]*");
    private static final Pattern DUE_FIELD = Pattern.compile("time[1-9][0-9]*");
    private final Store store;
    private final AuthService auth;
    private final TaskService tasks;
    private final LearningService learning;
    private final NotificationService notifications;
    private final MediaService media;
    private final WxProvider wx;
    private final TransactionTemplate transactions;

    public ApiController(
            Store store,
            AuthService auth,
            TaskService tasks,
            LearningService learning,
            NotificationService notifications,
            MediaService media,
            WxProvider wx,
            TransactionTemplate transactions) {
        this.store = store;
        this.auth = auth;
        this.tasks = tasks;
        this.learning = learning;
        this.notifications = notifications;
        this.media = media;
        this.wx = wx;
        this.transactions = transactions;
    }

    @PostMapping("/admin/auth/login")
    public Object login(@RequestBody Map<String, Object> input, HttpServletRequest request) {
        return ApiAdvice.ok(auth.login(input, request.getRemoteAddr()));
    }

    @PostMapping("/admin/auth/logout")
    public Object logout(HttpServletRequest request) {
        Map<String, Object> actor = auth.actor(request);
        actor.put("status", "REVOKED");
        store.save(Store.Table.AUTH_TOKEN, actor, "REVOKED");
        return ApiAdvice.ok(Data.map("loggedOut", true));
    }

    @PostMapping("/auth/wechat-login")
    public Object wechatLogin(@RequestBody Map<String, Object> input, HttpServletRequest request) {
        return ApiAdvice.ok(auth.wechatLogin(input, request.getRemoteAddr()));
    }

    @GetMapping("/admin/materials/upload-limits")
    public Object uploadLimits(HttpServletRequest request) {
        auth.admin(request);
        return ApiAdvice.ok(media.uploadLimits());
    }

    @GetMapping("/me")
    public Object me(HttpServletRequest request) {
        return ApiAdvice.ok(auth.me(auth.actor(request)));
    }

    @GetMapping("/admin/people")
    public Object people(HttpServletRequest request) {
        auth.admin(request);
        return page(
                store.all(Store.Table.PERSON).stream().map(StoredRecordDO::toBoundary).toList(),
                request);
    }

    @PostMapping("/admin/people")
    public Object person(@RequestBody Map<String, Object> input, HttpServletRequest request) {
        Map<String, Object> actor = auth.admin(request);
        Map<String, Object> person =
                Data.map(
                        "personNo",
                        Data.required(input, "personNo"),
                        "name",
                        Data.required(input, "name"),
                        "unit",
                        Data.str(input, "unit"),
                        "project",
                        Data.str(input, "project"),
                        "status",
                        "ACTIVE");
        store.create(
                Store.Table.PERSON, person, null, null, "ACTIVE", Data.str(person, "personNo"));
        store.audit(Data.str(actor, "actorId"), "PERSON_CREATED", Data.str(person, "id"), Map.of());
        return ApiAdvice.ok(Store.publicView(person));
    }

    @PatchMapping("/admin/people/{id}")
    public Object editPerson(
            @PathVariable String id,
            @RequestBody Map<String, Object> input,
            HttpServletRequest request) {
        Map<String, Object> actor = auth.admin(request);
        return ApiAdvice.ok(
                transactions.execute(
                        status -> {
                            Map<String, Object> person =
                                    store.lock(Store.Table.PERSON, id).toBoundary();
                            checkRevision(person, input);
                            for (String field : List.of("name", "unit", "project", "status")) {
                                if (input.containsKey(field)) {
                                    person.put(field, input.get(field));
                                }
                            }
                            Data.require(
                                    List.of("ACTIVE", "INACTIVE")
                                            .contains(Data.str(person, "status")),
                                    "INVALID_INPUT",
                                    "Invalid person status");
                            store.save(Store.Table.PERSON, person, Data.str(person, "status"));
                            store.audit(
                                    Data.str(actor, "actorId"),
                                    "PERSON_UPDATED",
                                    id,
                                    Store.publicView(person));
                            if ("ACTIVE".equals(person.get("status"))) {
                                notifications.requestDispatch(id);
                            }
                            return Store.publicView(person);
                        }));
    }

    @PostMapping("/admin/people/{id}/invitation")
    public Object invitation(@PathVariable String id, HttpServletRequest request) {
        return ApiAdvice.ok(auth.invitation(id, Data.str(auth.admin(request), "actorId")));
    }

    @PostMapping("/me/binding-requests")
    public Object bind(@RequestBody Map<String, Object> input, HttpServletRequest request) {
        return ApiAdvice.ok(
                auth.publicBinding(
                        auth.requestBinding(
                                auth.actor(request), Data.required(input, "inviteCode"))));
    }

    @GetMapping("/admin/bindings")
    public Object bindings(HttpServletRequest request) {
        auth.admin(request);
        return page(
                store.all(Store.Table.WX_IDENTITY_BINDING).stream()
                        .map(StoredRecordDO::toBoundary)
                        .toList()
                        .stream()
                        .map(auth::publicBinding)
                        .toList(),
                request);
    }

    @PostMapping("/admin/bindings/{id}/decision")
    public Object decision(
            @PathVariable String id,
            @RequestBody Map<String, Object> input,
            HttpServletRequest request) {
        return ApiAdvice.ok(auth.decision(id, input, Data.str(auth.admin(request), "actorId")));
    }

    @PostMapping("/admin/bindings/{id}/revoke")
    public Object revoke(
            @PathVariable String id,
            @RequestBody Map<String, Object> input,
            HttpServletRequest request) {
        auth.revoke(id, Data.str(auth.admin(request), "actorId"), Data.required(input, "reason"));
        return ApiAdvice.ok(Data.map("status", "REVOKED"));
    }

    @PostMapping("/admin/tasks")
    public Object createTask(@RequestBody Map<String, Object> input, HttpServletRequest request) {
        return ApiAdvice.ok(
                Store.publicView(tasks.create(input, Data.str(auth.admin(request), "actorId"))));
    }

    @PatchMapping("/admin/tasks/{id}")
    public Object editTask(
            @PathVariable String id,
            @RequestBody Map<String, Object> input,
            HttpServletRequest request) {
        auth.admin(request);
        return ApiAdvice.ok(tasks.edit(id, input));
    }

    @GetMapping("/admin/tasks")
    public Object taskList(HttpServletRequest request) {
        auth.admin(request);
        return page(
                store.all(Store.Table.TASK).stream().map(StoredRecordDO::toBoundary).toList(),
                request);
    }

    @GetMapping("/admin/tasks/{id}")
    public Object task(@PathVariable String id, HttpServletRequest request) {
        auth.admin(request);
        return ApiAdvice.ok(Store.publicView(store.get(Store.Table.TASK, id).toBoundary()));
    }

    @PostMapping("/admin/tasks/{id}/publish")
    public Object publish(@PathVariable String id, HttpServletRequest request) {
        return ApiAdvice.ok(
                tasks.publish(id, key(request), Data.str(auth.admin(request), "actorId")));
    }

    @PostMapping("/admin/tasks/{id}/cancel")
    public Object cancel(
            @PathVariable String id,
            @RequestBody Map<String, Object> input,
            HttpServletRequest request) {
        tasks.cancel(id, Data.required(input, "reason"), Data.str(auth.admin(request), "actorId"));
        return ApiAdvice.ok(Data.map("status", "CANCELLED"));
    }

    @GetMapping("/admin/tasks/{id}/assignments")
    public Object taskAssignments(@PathVariable String id, HttpServletRequest request) {
        auth.admin(request);
        return page(
                store.refs(Store.Table.ASSIGNMENT, id).stream()
                        .map(StoredRecordDO::toBoundary)
                        .toList()
                        .stream()
                        .map(tasks::summary)
                        .toList(),
                request);
    }

    @GetMapping("/admin/assignments/{id}")
    public Object adminAssignment(@PathVariable String id, HttpServletRequest request) {
        return ApiAdvice.ok(tasks.detail(id, auth.admin(request)));
    }

    @GetMapping("/me/assignments")
    public Object assignments(HttpServletRequest request) {
        return page(tasks.mine(auth.actor(request)), request);
    }

    @GetMapping({"/me/assignments/{id}", "/me/assignments/{id}/progress"})
    public Object assignment(@PathVariable String id, HttpServletRequest request) {
        return ApiAdvice.ok(tasks.detail(id, auth.actor(request)));
    }

    @PostMapping("/me/assignments/{id}/entered")
    public Object entered(
            @PathVariable String id,
            @RequestBody Map<String, Object> input,
            HttpServletRequest request) {
        Map<String, Object> actor = auth.actor(request);
        auth.binding(actor, true);
        tasks.authorizeAssignment(id, actor);
        return ApiAdvice.ok(
                transactions.execute(
                        status -> {
                            Map<String, Object> assignment =
                                    store.lock(Store.Table.ASSIGNMENT, id).toBoundary();
                            assignment.putIfAbsent("firstEnteredAt", Data.now());
                            store.save(
                                    Store.Table.ASSIGNMENT,
                                    assignment,
                                    Data.str(assignment, "status"));
                            String noticeId = Data.str(input, "notificationId");
                            if (!noticeId.isBlank()) {
                                Map<String, Object> notice =
                                        store.lock(Store.Table.NOTIFICATION, noticeId).toBoundary();
                                Data.require(
                                        id.equals(notice.get("assignmentId"))
                                                && assignment
                                                        .get("personId")
                                                        .equals(notice.get("personId")),
                                        "FORBIDDEN",
                                        "Notification does not belong to this assignment");
                                if (notice.get("openedAt") == null) {
                                    notice.put("openedAt", Data.now());
                                    store.save(
                                            Store.Table.NOTIFICATION,
                                            notice,
                                            Data.str(notice, "sendStatus"));
                                }
                            }
                            return Data.map("firstEnteredAt", assignment.get("firstEnteredAt"));
                        }));
    }

    @PostMapping("/me/assignments/{id}/media-ticket")
    public Object ticket(
            @PathVariable String id,
            @RequestBody Map<String, Object> input,
            HttpServletRequest request) {
        Map<String, Object> assignment = tasks.authorizeAssignment(id, auth.actor(request));
        Map<String, Object> item =
                store.get(Store.Table.TASK_MATERIAL, Data.required(input, "taskMaterialId"))
                        .toBoundary();
        Data.require(
                assignment.get("taskId").equals(item.get("taskId")),
                "FORBIDDEN",
                "Material does not belong to assignment");
        Integer pageNo = input.containsKey("pageNo") ? (int) Data.num(input, "pageNo", 0) : null;
        return ApiAdvice.ok(media.ticket(Data.str(item, "materialId"), pageNo));
    }

    @PostMapping("/learning/sessions")
    public Object start(@RequestBody Map<String, Object> input, HttpServletRequest request) {
        return ApiAdvice.ok(learning.start(auth.actor(request), input, null));
    }

    @PostMapping("/learning/sessions/{id}/takeover")
    public Object takeover(
            @PathVariable String id,
            @RequestBody Map<String, Object> input,
            HttpServletRequest request) {
        Data.require(
                id.equals(Data.str(input, "expectedSessionId")),
                "SESSION_CHANGED",
                "Confirm the current session");
        return ApiAdvice.ok(learning.start(auth.actor(request), input, id));
    }

    @PostMapping("/learning/sessions/{id}/events:batch")
    public Object events(
            @PathVariable String id,
            @RequestBody Map<String, Object> input,
            HttpServletRequest request) {
        return ApiAdvice.ok(learning.events(id, auth.actor(request), input));
    }

    @PostMapping("/learning/sessions/{id}/close")
    public Object close(@PathVariable String id, HttpServletRequest request) {
        return ApiAdvice.ok(learning.close(id, auth.actor(request)));
    }

    @PostMapping("/me/assignments/{id}/confirm")
    public Object confirm(
            @PathVariable String id,
            @RequestBody Map<String, Object> input,
            HttpServletRequest request) {
        return ApiAdvice.ok(tasks.confirm(id, auth.actor(request), input, key(request)));
    }

    @GetMapping("/me/notification-settings")
    public Object settings(HttpServletRequest request) {
        auth.binding(auth.actor(request), true);
        String template =
                Data.str(
                        store.get(Store.Table.APP_CONFIG, "notification").toBoundary(),
                        "templateId");
        return ApiAdvice.ok(
                Data.map(
                        "templateId",
                        template,
                        "configured",
                        wx.templateConfigured(template),
                        "providerMode",
                        wx.mode(),
                        "preference",
                        "UNKNOWN"));
    }

    @PostMapping("/me/subscriptions")
    public Object subscribe(HttpServletRequest request) {
        return ApiAdvice.ok(notifications.subscribe(auth.actor(request)));
    }

    @PostMapping("/me/subscriptions/{id}/result")
    public Object subscription(
            @PathVariable String id,
            @RequestBody Map<String, Object> input,
            HttpServletRequest request) {
        return ApiAdvice.ok(notifications.subscriptionResult(id, auth.actor(request), input));
    }

    @GetMapping("/admin/tasks/{id}/notifications")
    public Object notices(@PathVariable String id, HttpServletRequest request) {
        auth.admin(request);
        return page(
                store.all(Store.Table.NOTIFICATION).stream()
                        .map(StoredRecordDO::toBoundary)
                        .toList()
                        .stream()
                        .filter(row -> id.equals(row.get("taskId")))
                        .toList(),
                request);
    }

    @GetMapping("/admin/notifications/{id}")
    public Object notice(@PathVariable String id, HttpServletRequest request) {
        auth.admin(request);
        return ApiAdvice.ok(noticeDetail(id));
    }

    public Map<String, Object> noticeDetail(String id) {
        Map<String, Object> result =
                Store.publicView(store.get(Store.Table.NOTIFICATION, id).toBoundary());
        result.put(
                "attempts",
                store.refs(Store.Table.NOTIFICATION_ATTEMPT, id).stream()
                        .map(StoredRecordDO::toBoundary)
                        .toList()
                        .stream()
                        .map(Store::publicView)
                        .toList());
        return result;
    }

    @PostMapping("/admin/tasks/{id}/reminders")
    public Object reminders(
            @PathVariable String id,
            @RequestBody Map<String, Object> input,
            HttpServletRequest request) {
        return ApiAdvice.ok(
                notifications.remind(
                        id, input, key(request), Data.str(auth.admin(request), "actorId")));
    }

    @GetMapping("/admin/notification-config")
    public Object config(HttpServletRequest request) {
        auth.admin(request);
        Map<String, Object> result =
                Store.publicView(store.get(Store.Table.APP_CONFIG, "notification").toBoundary());
        result.putAll(
                Data.map(
                        "providerMode",
                        wx.mode(),
                        "configured",
                        wx.templateConfigured(Data.str(result, "templateId")),
                        "callbackPath",
                        "/integrations/wechat/yongsen/callback"));
        return ApiAdvice.ok(result);
    }

    @PutMapping("/admin/notification-config")
    public Object setConfig(@RequestBody Map<String, Object> input, HttpServletRequest request) {
        Map<String, Object> actor = auth.admin(request);
        return ApiAdvice.ok(
                transactions.execute(
                        status -> {
                            Map<String, Object> config =
                                    store.lock(Store.Table.APP_CONFIG, "notification").toBoundary();
                            checkRevision(config, input);
                            Map<String, Object> mapping = Data.obj(input.get("fieldMapping"));
                            Data.require(
                                    mapping.containsKey("title") && mapping.size() <= 2,
                                    "INVALID_MAPPING",
                                    "必须配置培训名称字段，可选截止时间字段");
                            for (Map.Entry<String, Object> entry : mapping.entrySet()) {
                                Object value = entry.getValue();
                                Data.require(
                                        value instanceof String
                                                && (("title".equals(entry.getKey())
                                                                && TITLE_FIELD
                                                                        .matcher((String) value)
                                                                        .matches())
                                                        || ("dueAt".equals(entry.getKey())
                                                                && DUE_FIELD
                                                                        .matcher((String) value)
                                                                        .matches())),
                                        "INVALID_MAPPING",
                                        "培训名称需对应thing字段，截止时间需对应time字段");
                            }
                            Data.require(
                                    input.get("templateId") instanceof String,
                                    "INVALID_TEMPLATE",
                                    "订阅消息模板ID必须为文本");
                            String template = Data.required(input, "templateId");
                            Data.require(
                                    TEMPLATE_ID.matcher(template).matches(),
                                    "INVALID_TEMPLATE",
                                    "订阅消息模板ID格式不正确");
                            config.putAll(
                                    Data.map("templateId", template, "fieldMapping", mapping));
                            store.save(Store.Table.APP_CONFIG, config, "ACTIVE");
                            store.audit(
                                    Data.str(actor, "actorId"),
                                    "NOTIFICATION_CONFIG",
                                    "notification",
                                    Store.publicView(config));
                            notifications.requestDispatch("all");
                            return Store.publicView(config);
                        }));
    }

    @PostMapping("/admin/notification-config/check")
    public Object checkConfig(HttpServletRequest request) {
        auth.admin(request);
        return ApiAdvice.ok(
                Data.map(
                        "configured",
                        wx.templateConfigured(
                                Data.str(
                                        store.get(Store.Table.APP_CONFIG, "notification")
                                                .toBoundary(),
                                        "templateId")),
                        "providerMode",
                        wx.mode(),
                        "callbackConfigured",
                        wx.callbackConfigured(),
                        "liveDeliveryVerified",
                        false));
    }

    @GetMapping("/admin/tasks/{id}/records.csv")
    public ResponseEntity<String> csv(@PathVariable String id, HttpServletRequest request) {
        Map<String, Object> actor = auth.admin(request);
        StringBuilder output =
                new StringBuilder(
                        "\ufeff记录编号,人员编号,姓名,单位,项目,任务,发布时间,截止时间,任务状态,学习状态,材料与SHA256,学习进度摘要,");
        output.append("有效学习毫秒,开始学习,最后学习,确认时间,完成时间,确认快照SHA256,通知发送与送达摘要,通知进入时间,取消原因,导出时间,通知渠道\r\n");
        Map<String, Object> task = store.get(Store.Table.TASK, id).toBoundary();
        String exportedAt = Data.now();
        List<Map<String, Object>> materials = tasks.materials(id);
        String materialSummary =
                materials.stream()
                        .map(
                                item ->
                                        Data.str(item, "title")
                                                + " ["
                                                + Data.str(
                                                        Data.obj(item.get("materialSnapshot")),
                                                        "sha256")
                                                + "]")
                        .collect(java.util.stream.Collectors.joining("; "));
        for (Map<String, Object> assignment :
                store.refs(Store.Table.ASSIGNMENT, id).stream()
                        .map(StoredRecordDO::toBoundary)
                        .toList()) {
            Map<String, Object> person = Data.obj(assignment.get("personSnapshot"));
            String assignmentId = Data.str(assignment, "id");
            List<Map<String, Object>> progress =
                    store.refs(Store.Table.LEARNING_PROGRESS, assignmentId).stream()
                            .map(StoredRecordDO::toBoundary)
                            .toList();
            String progressSummary =
                    progress.stream()
                            .map(
                                    row ->
                                            Data.str(row, "taskMaterialId")
                                                    + ":coverage="
                                                    + Data.decimal(row, "coverageRatio", 0)
                                                    + ",requirementsMet="
                                                    + Data.bool(row, "requirementsMet"))
                            .collect(java.util.stream.Collectors.joining("; "));
            List<Map<String, Object>> notices =
                    store.refs(Store.Table.NOTIFICATION, assignmentId).stream()
                            .map(StoredRecordDO::toBoundary)
                            .toList();
            String notificationSummary =
                    notices.stream()
                            .map(
                                    row ->
                                            Data.str(row, "id")
                                                    + ":"
                                                    + Data.str(row, "sendStatus")
                                                    + "/"
                                                    + Data.str(row, "deliveryStatus"))
                            .collect(java.util.stream.Collectors.joining("; "));
            String opened =
                    notices.stream()
                            .filter(row -> !Data.str(row, "openedAt").isBlank())
                            .map(row -> Data.str(row, "id") + ":" + Data.str(row, "openedAt"))
                            .collect(java.util.stream.Collectors.joining("; "));
            List<Object> values =
                    java.util.Arrays.asList(
                            assignmentId,
                            person.get("personNo"),
                            person.get("name"),
                            person.get("unit"),
                            person.get("project"),
                            task.get("title"),
                            task.get("publishedAt"),
                            task.get("dueAt"),
                            task.get("status"),
                            assignment.get("status"),
                            materialSummary,
                            progressSummary,
                            progress.stream()
                                    .mapToLong(row -> Data.num(row, "effectiveMs", 0))
                                    .sum(),
                            assignment.get("firstLearningAt"),
                            assignment.get("lastLearningAt"),
                            assignment.get("confirmedAt"),
                            assignment.get("completedAt"),
                            assignment.get("confirmationHash"),
                            notificationSummary,
                            opened,
                            task.get("cancellationReason"),
                            exportedAt,
                            wx.mode());
            output.append(
                            values.stream()
                                    .map(RecordRenderer::csvField)
                                    .collect(java.util.stream.Collectors.joining(",")))
                    .append("\r\n");
        }
        store.audit(Data.str(actor, "actorId"), "CSV_EXPORTED", id, Map.of());
        return ResponseEntity.ok()
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=training-records.csv")
                .contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
                .body(output.toString());
    }

    @GetMapping({"/admin/assignments/{id}/print", "/me/assignments/{id}/print"})
    public ResponseEntity<String> print(@PathVariable String id, HttpServletRequest request) {
        Map<String, Object> actor =
                request.getRequestURI().contains("/admin/")
                        ? auth.admin(request)
                        : auth.actor(request);
        Map<String, Object> detail = tasks.detail(id, actor);
        Map<String, Object> assignment = store.get(Store.Table.ASSIGNMENT, id).toBoundary();
        detail.put(
                "notifications",
                store.refs(Store.Table.NOTIFICATION, id).stream()
                        .map(StoredRecordDO::toBoundary)
                        .map(Store::publicView)
                        .toList());
        String html = RecordRenderer.render(detail, assignment, wx.mode());
        store.audit(Data.str(actor, "actorId"), "RECORD_PRINTED", id, Map.of());
        return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).body(html);
    }

    public static Object page(List<Map<String, Object>> input, HttpServletRequest request) {
        String search = request.getParameter("q");
        String state = request.getParameter("status");
        List<Map<String, Object>> rows =
                input.stream()
                        .filter(row -> search == null || Data.json(row).contains(search))
                        .filter(
                                row ->
                                        state == null
                                                || state.isBlank()
                                                || state.equals(row.get("status")))
                        .toList();
        int limit = 100;
        String cursor = request.getParameter("cursor");
        int start = 0;
        if (cursor != null) {
            for (int index = 0; index < rows.size(); index++) {
                if (cursor.equals(rows.get(index).get("id"))) {
                    start = index + 1;
                    break;
                }
            }
        }
        int end = Math.min(start + limit, rows.size());
        List<Map<String, Object>> result =
                rows.subList(start, end).stream().map(Store::publicView).toList();
        return ApiAdvice.ok(
                Data.map(
                        "items",
                        result,
                        "nextCursor",
                        end < rows.size() ? rows.get(end - 1).get("id") : null));
    }

    private String key(HttpServletRequest request) {
        String key = request.getHeader("Idempotency-Key");
        return key == null ? "" : key;
    }

    private void checkRevision(Map<String, Object> record, Map<String, Object> input) {
        if (input.containsKey("expectedRevision")
                && Data.num(input, "expectedRevision", 0) != Data.num(record, "rowVersion", 0)) {
            throw new BusinessException("VERSION_CONFLICT", "Refresh before updating", 409);
        }
    }
}
