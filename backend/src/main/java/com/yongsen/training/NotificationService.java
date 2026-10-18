package com.yongsen.training;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

@Service
public class NotificationService {
    private static final Logger LOGGER = LoggerFactory.getLogger(NotificationService.class);
    private static final long REQUEST_TTL_MS = 300000;
    private static final long SEND_LEASE_MS = 60000;
    private static final String DEADLINE_REQUIRED_MESSAGE =
            "当前微信通知模板要求截止时间，请填写培训完成截止时间，或更换不含截止时间的模板";
    private static final List<String> PENDING_STATES =
            List.of("WAITING_CONFIGURATION", "WAITING_BINDING", "WAITING_SUBSCRIPTION", "READY");
    private final Store store;
    private final AuthService auth;
    private final WxProvider wx;
    private final TransactionTemplate transactions;

    public NotificationService(
            Store store, AuthService auth, WxProvider wx, TransactionTemplate transactions) {
        this.store = store;
        this.auth = auth;
        this.wx = wx;
        this.transactions = transactions;
    }

    private BackgroundWorkDispatcher background;

    @Autowired
    public void setBackground(BackgroundWorkDispatcher background) {
        this.background = background;
    }

    public void requestDispatch(String personIdOrAll) {
        if (background != null) {
            background.scheduleAfterCommit("NOTIFICATIONS", personIdOrAll);
        }
    }

    public void validateTaskForNotification(Map<String, Object> task) {
        Map<String, Object> config = store.get(Store.Table.APP_CONFIG, "notification").toBoundary();
        Data.require(
                !wx.templateConfigured(Data.str(config, "templateId"))
                        || !missingDeadline(config, task),
                "NOTIFICATION_DEADLINE_REQUIRED",
                DEADLINE_REQUIRED_MESSAGE);
    }

    private boolean missingDeadline(Map<String, Object> config, Map<String, Object> task) {
        return Data.obj(config.get("fieldMapping")).containsKey("dueAt")
                && Data.str(task, "dueAt").isBlank();
    }

    @Transactional
    public Map<String, Object> create(
            Map<String, Object> assignment, String kind, String operation, String previous) {
        String assignmentId = Data.str(assignment, "id");
        List<Map<String, Object>> existing =
                store.refs(Store.Table.NOTIFICATION, assignmentId).stream()
                        .map(StoredRecordDO::toBoundary)
                        .toList();
        Map<String, Object> same =
                existing.stream()
                        .filter(row -> operation.equals(row.get("operationKey")))
                        .findFirst()
                        .orElse(null);
        if (same != null) {
            requestDispatch("all");
            return same;
        }
        Map<String, Object> pending =
                existing.stream()
                        .filter(
                                row ->
                                        PENDING_STATES.contains(Data.str(row, "sendStatus"))
                                                || "SENDING".equals(row.get("sendStatus")))
                        .findFirst()
                        .orElse(null);
        if (pending != null) {
            requestDispatch("all");
            return pending;
        }
        Map<String, Object> created =
                store.create(
                        Store.Table.NOTIFICATION,
                        Data.map(
                                "assignmentId",
                                assignmentId,
                                "personId",
                                assignment.get("personId"),
                                "taskId",
                                assignment.get("taskId"),
                                "kind",
                                kind,
                                "operationKey",
                                operation,
                                "sendStatus",
                                "WAITING_BINDING",
                                "deliveryStatus",
                                "NOT_SENT",
                                "openedAt",
                                null,
                                "previousNotificationId",
                                previous,
                                "providerMode",
                                wx.mode(),
                                "attemptCount",
                                0L),
                        assignmentId,
                        Data.str(assignment, "personId"),
                        "WAITING_BINDING",
                        "active:" + assignmentId);
        requestDispatch("all");
        return created;
    }

    @Transactional
    public Map<String, Object> subscribe(Map<String, Object> actor) {
        Map<String, Object> binding = auth.binding(actor, true);
        auth.rate("subscribe:" + actor.get("actorId"));
        String template =
                Data.str(
                        store.get(Store.Table.APP_CONFIG, "notification").toBoundary(),
                        "templateId");
        Data.require(
                wx.templateConfigured(template),
                "TEMPLATE_NOT_CONFIGURED",
                "Notification template is not configured");
        String nonce = Data.token();
        long expiry = System.currentTimeMillis() + REQUEST_TTL_MS;
        Map<String, Object> row =
                store.create(
                        Store.Table.SUBSCRIPTION_RECORD,
                        Data.map(
                                "bindingId",
                                binding.get("id"),
                                "identityId",
                                actor.get("actorId"),
                                "templateId",
                                template,
                                "nonceHash",
                                Data.hash(nonce),
                                "expiresMs",
                                expiry,
                                "result",
                                "PENDING",
                                "invalidated",
                                false),
                        Data.str(binding, "id"),
                        template,
                        "PENDING",
                        null);
        return Data.map(
                "id",
                row.get("id"),
                "requestId",
                row.get("id"),
                "nonce",
                nonce,
                "templateId",
                template,
                "expiresAt",
                Instant.ofEpochMilli(expiry).toString());
    }

    @Transactional
    public Map<String, Object> subscriptionResult(
            String id, Map<String, Object> actor, Map<String, Object> input) {
        Map<String, Object> binding = auth.binding(actor, true);
        Map<String, Object> row = store.lock(Store.Table.SUBSCRIPTION_RECORD, id).toBoundary();
        Data.require(
                binding.get("id").equals(row.get("bindingId")),
                "FORBIDDEN",
                "Not your subscription");
        Data.require(
                Data.hash(Data.required(input, "nonce")).equals(row.get("nonceHash")),
                "INVALID_NONCE",
                "Invalid nonce");
        String result = Data.required(input, "result");
        Data.require(
                List.of("accept", "reject", "ban", "filter").contains(result),
                "INVALID_INPUT",
                "Invalid subscription result");
        if (!Data.str(row, "clientResult").isBlank()) {
            Data.require(
                    result.equals(row.get("clientResult")),
                    "RESULT_CONFLICT",
                    "Subscription result already recorded");
            return Data.map("recorded", true, "source", Data.str(row, "confidence"));
        }
        Data.require(
                Data.num(row, "expiresMs", 0) > System.currentTimeMillis(),
                "NONCE_EXPIRED",
                "Subscribe again");
        boolean observed = "PROVIDER_OBSERVED".equals(row.get("confidence"));
        row.putAll(
                Data.map(
                        "clientResult",
                        result,
                        "result",
                        observed ? row.get("result") : result,
                        "confidence",
                        observed ? "PROVIDER_OBSERVED" : "HINT_ONLY"));
        store.save(Store.Table.SUBSCRIPTION_RECORD, row, result);
        if ("reject".equals(result)) {
            invalidate(binding, Data.str(row, "templateId"));
        }
        requestDispatch(Data.str(binding, "personId"));
        return Data.map("recorded", true, "source", "HINT_ONLY");
    }

    private void invalidate(Map<String, Object> binding, String template) {
        for (Map<String, Object> record :
                store.refs(Store.Table.SUBSCRIPTION_RECORD, Data.str(binding, "id")).stream()
                        .map(StoredRecordDO::toBoundary)
                        .toList()) {
            if (template.equals(record.get("templateId"))) {
                record.put("invalidated", true);
                store.save(Store.Table.SUBSCRIPTION_RECORD, record, Data.str(record, "result"));
            }
        }
    }

    @Scheduled(fixedDelay = 1000)
    public void dispatch() {
        if (background != null && background.enabled()) {
            return;
        }
        List<StoredRecordDO> pending = new ArrayList<>();
        for (String state :
                List.of(
                        "WAITING_CONFIGURATION",
                        "WAITING_BINDING",
                        "WAITING_SUBSCRIPTION",
                        "READY",
                        "SENDING")) {
            pending.addAll(store.status(Store.Table.NOTIFICATION, state));
        }
        for (Map<String, Object> notice :
                pending.stream().map(StoredRecordDO::toBoundary).toList()) {
            try {
                if (PENDING_STATES.contains(Data.str(notice, "sendStatus"))) {
                    Map<String, Object> prepared =
                            transactions.execute(status -> prepare(Data.str(notice, "id")));
                    if (prepared != null) {
                        sendPrepared(prepared);
                    }
                } else if ("SENDING".equals(notice.get("sendStatus"))
                        && System.currentTimeMillis()
                                        - Data.num(notice, "startedMs", System.currentTimeMillis())
                                > SEND_LEASE_MS) {
                    transactions.executeWithoutResult(
                            status -> recoverUnknown(Data.str(notice, "id")));
                }
            } catch (BusinessException exception) {
                LOGGER.warn("Notification {} deferred: {}", notice.get("id"), exception.code());
            } catch (org.springframework.dao.DataAccessException exception) {
                LOGGER.error(
                        "Notification database operation failed for {}",
                        notice.get("id"),
                        exception);
            }
        }
    }

    public record DispatchBatch(String cursor, boolean more) {}

    /** One bounded event batch; waiting for a human does not cause recurring function calls. */
    public DispatchBatch dispatchBatch(String personIdOrAll, String cursor) {
        List<Map<String, Object>> pending = new ArrayList<>();
        for (String state :
                List.of(
                        "WAITING_CONFIGURATION",
                        "WAITING_BINDING",
                        "WAITING_SUBSCRIPTION",
                        "READY",
                        "SENDING")) {
            pending.addAll(
                    store.status(Store.Table.NOTIFICATION, state).stream()
                            .map(StoredRecordDO::toBoundary)
                            .filter(
                                    row ->
                                            "all".equals(personIdOrAll)
                                                    || personIdOrAll.equals(row.get("personId")))
                            .filter(row -> Data.str(row, "id").compareTo(cursor) > 0)
                            .toList());
        }
        pending.sort(Comparator.comparing(row -> Data.str(row, "id")));
        String next = cursor;
        for (Map<String, Object> notice : pending.stream().limit(5).toList()) {
            String id = Data.str(notice, "id");
            if ("SENDING".equals(notice.get("sendStatus"))) {
                if (System.currentTimeMillis() - Data.num(notice, "startedMs", 0)
                        <= SEND_LEASE_MS) {
                    throw new BusinessException(
                            "NOTIFICATION_BUSY", "Notification lease is active", 503);
                }
                transactions.executeWithoutResult(status -> recoverUnknown(id));
            } else {
                Map<String, Object> prepared = transactions.execute(status -> prepare(id));
                if (prepared != null) {
                    sendPrepared(prepared);
                }
                Map<String, Object> updated = store.get(Store.Table.NOTIFICATION, id).toBoundary();
                Map<String, Object> person =
                        store.get(Store.Table.PERSON, Data.str(updated, "personId")).toBoundary();
                if (PENDING_STATES.contains(Data.str(updated, "sendStatus"))
                        && Data.num(person, "sendLeaseUntil", 0) > System.currentTimeMillis()) {
                    throw new BusinessException(
                            "NOTIFICATION_BUSY", "Recipient send lease is active", 503);
                }
                if ("READY".equals(updated.get("sendStatus"))
                        || "SENDING".equals(updated.get("sendStatus"))) {
                    throw new BusinessException(
                            "NOTIFICATION_RETRY", "Notification retry is pending", 503);
                }
            }
            next = id;
        }
        return new DispatchBatch(next, pending.size() > 5);
    }

    private Map<String, Object> prepare(String id) {
        Map<String, Object> notice = store.lock(Store.Table.NOTIFICATION, id).toBoundary();
        if (!PENDING_STATES.contains(Data.str(notice, "sendStatus"))) {
            return null;
        }
        if (Data.num(notice, "nextAttemptMs", 0) > System.currentTimeMillis()) {
            return null;
        }
        Map<String, Object> assignment =
                store.get(Store.Table.ASSIGNMENT, Data.str(notice, "assignmentId")).toBoundary();
        Map<String, Object> task =
                store.get(Store.Table.TASK, Data.str(assignment, "taskId")).toBoundary();
        Map<String, Object> person =
                store.lock(Store.Table.PERSON, Data.str(notice, "personId")).toBoundary();
        if (!"ACTIVE".equals(task.get("status"))
                || "COMPLETED".equals(assignment.get("status"))
                || !"ACTIVE".equals(person.get("status"))) {
            change(notice, "CANCELED", "NOT_SENT");
            return null;
        }
        Map<String, Object> config = store.get(Store.Table.APP_CONFIG, "notification").toBoundary();
        if (!wx.templateConfigured(Data.str(config, "templateId"))) {
            return waitingForConfiguration(notice, "尚未配置可用的微信通知模板");
        }
        if (missingDeadline(config, task)) {
            return waitingForConfiguration(notice, DEADLINE_REQUIRED_MESSAGE);
        }
        if (notice.containsKey("errorMessage")) {
            notice.remove("errorMessage");
            store.save(Store.Table.NOTIFICATION, notice, Data.str(notice, "sendStatus"));
        }
        Map<String, Object> binding =
                store.all(Store.Table.WX_IDENTITY_BINDING).stream()
                        .map(StoredRecordDO::toBoundary)
                        .toList()
                        .stream()
                        .filter(
                                row ->
                                        person.get("id").equals(row.get("personId"))
                                                && "ACTIVE".equals(row.get("status")))
                        .findFirst()
                        .orElse(null);
        if (binding == null) {
            return waiting(notice, "WAITING_BINDING");
        }
        if (Data.num(person, "sendLeaseUntil", 0) > System.currentTimeMillis()) {
            return null;
        }
        Map<String, Object> hint =
                store.refs(Store.Table.SUBSCRIPTION_RECORD, Data.str(binding, "id")).stream()
                        .map(StoredRecordDO::toBoundary)
                        .toList()
                        .stream()
                        .filter(
                                row ->
                                        "accept".equals(row.get("result"))
                                                && !Data.bool(row, "invalidated")
                                                && (Data.str(row, "hintUsedByNotificationId")
                                                                .isBlank()
                                                        || (id.equals(
                                                                        row.get(
                                                                                "hintUsedByNotificationId"))
                                                                && row.get("id")
                                                                        .equals(
                                                                                notice.get(
                                                                                        "retrySubscriptionId"))))
                                                && config.get("templateId")
                                                        .equals(row.get("templateId"))
                                                && (Data.str(notice, "preferredSubscriptionId")
                                                                .isBlank()
                                                        || row.get("id")
                                                                .equals(
                                                                        notice.get(
                                                                                "preferredSubscriptionId"))))
                        .findFirst()
                        .orElse(null);
        if (hint == null) {
            return waiting(notice, "WAITING_SUBSCRIPTION");
        }
        hint = store.lock(Store.Table.SUBSCRIPTION_RECORD, Data.str(hint, "id")).toBoundary();
        hint.put("hintUsedByNotificationId", id);
        store.save(Store.Table.SUBSCRIPTION_RECORD, hint, "accept");
        long now = System.currentTimeMillis();
        Map<String, Object> payload = templatePayload(config, task);
        Map<String, Object> attempt =
                store.create(
                        Store.Table.NOTIFICATION_ATTEMPT,
                        Data.map(
                                "notificationId",
                                id,
                                "bindingId",
                                binding.get("id"),
                                "subscriptionRecordId",
                                hint.get("id"),
                                "templateSnapshot",
                                Store.publicView(config),
                                "payloadSnapshot",
                                payload,
                                "sendStatus",
                                "SENDING",
                                "deliveryStatus",
                                "UNKNOWN",
                                "startedMs",
                                now),
                        id,
                        null,
                        "SENDING",
                        null);
        notice.putAll(
                Data.map(
                        "startedMs", now, "attemptCount", Data.num(notice, "attemptCount", 0) + 1));
        change(notice, "SENDING", "UNKNOWN");
        person.putAll(Data.map("sendLeaseUntil", now + SEND_LEASE_MS, "activeNotificationId", id));
        store.save(Store.Table.PERSON, person, "ACTIVE");
        return Data.map(
                "notice", notice, "attempt", attempt, "binding", binding, "config", config,
                "payload", payload, "task", task);
    }

    private Map<String, Object> waitingForConfiguration(
            Map<String, Object> notice, String message) {
        if (!"WAITING_CONFIGURATION".equals(notice.get("sendStatus"))
                || !message.equals(notice.get("errorMessage"))) {
            notice.put("errorMessage", message);
            change(notice, "WAITING_CONFIGURATION", "NOT_SENT");
        }
        return null;
    }

    private Map<String, Object> waiting(Map<String, Object> notice, String state) {
        if (!state.equals(notice.get("sendStatus"))) {
            change(notice, state, "NOT_SENT");
        }
        return null;
    }

    private Map<String, Object> templatePayload(
            Map<String, Object> config, Map<String, Object> task) {
        Map<String, Object> mapping = Data.obj(config.get("fieldMapping"));
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        String title = Data.str(task, "title");
        if (mapping.containsKey("title")) {
            payload.put(
                    Data.str(mapping, "title"),
                    Data.map(
                            "value",
                            title.substring(
                                    0,
                                    title.offsetByCodePoints(
                                            0,
                                            Math.min(
                                                    title.codePointCount(0, title.length()),
                                                    20)))));
        }
        if (mapping.containsKey("dueAt") && !Data.str(task, "dueAt").isBlank()) {
            String date =
                    java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
                            .withZone(java.time.ZoneId.of("Asia/Shanghai"))
                            .format(Instant.parse(Data.str(task, "dueAt")));
            payload.put(Data.str(mapping, "dueAt"), Data.map("value", date));
        }
        return payload;
    }

    private void sendPrepared(Map<String, Object> prepared) {
        Map<String, Object> notice = Data.obj(prepared.get("notice"));
        String outcome = Data.str(notice, "simulateOutcome");
        WxProvider.SendResult result;
        String page =
                "pages/task/index?assignmentId="
                        + notice.get("assignmentId")
                        + "&notificationId="
                        + notice.get("id");
        if (auth.local() && "UNKNOWN".equals(outcome)) {
            result = new WxProvider.SendResult(-1, "Injected lost response after send", null, true);
        } else if (auth.local() && "FAILED".equals(outcome)) {
            result = new WxProvider.SendResult(40037, "Injected explicit rejection", null, false);
        } else {
            result =
                    wx.send(
                            auth.openid(Data.obj(prepared.get("binding"))),
                            Data.str(Data.obj(prepared.get("config")), "templateId"),
                            Data.obj(prepared.get("payload")),
                            page);
        }
        WxProvider.SendResult finalResult = result;
        transactions.executeWithoutResult(status -> finish(prepared, finalResult, page));
    }

    private void finish(Map<String, Object> prepared, WxProvider.SendResult result, String page) {
        Map<String, Object> notice =
                store.lock(
                                Store.Table.NOTIFICATION,
                                Data.str(Data.obj(prepared.get("notice")), "id"))
                        .toBoundary();
        Map<String, Object> attempt =
                store.lock(
                                Store.Table.NOTIFICATION_ATTEMPT,
                                Data.str(Data.obj(prepared.get("attempt")), "id"))
                        .toBoundary();
        String state = result.unknown() ? "UNKNOWN" : result.errcode() == 0 ? "ACCEPTED" : "FAILED";
        String delivery = result.unknown() || result.errcode() == 0 ? "UNKNOWN" : "NOT_SENT";
        attempt.putAll(
                Data.map(
                        "sendStatus",
                        state,
                        "deliveryStatus",
                        delivery,
                        "sendErrcode",
                        result.errcode(),
                        "providerMsgId",
                        result.msgid(),
                        "respondedAt",
                        Data.now()));
        store.save(Store.Table.NOTIFICATION_ATTEMPT, attempt, state);
        notice.put("error", result.errmsg());
        notice.put("_key", null);
        change(notice, state, delivery);
        if (result.errcode() == 43101 && !result.unknown()) {
            invalidate(
                    Data.obj(prepared.get("binding")),
                    Data.str(Data.obj(prepared.get("config")), "templateId"));
            notice.put("_key", "active:" + notice.get("assignmentId"));
            change(notice, "WAITING_SUBSCRIPTION", "NOT_SENT");
        } else if (!result.unknown()
                && result.errcode() == -1
                && Data.num(notice, "attemptCount", 0) < 3) {
            // Only an explicit provider system-busy rejection is safe to retry automatically.
            notice.putAll(
                    Data.map(
                            "retrySubscriptionId",
                            attempt.get("subscriptionRecordId"),
                            "nextAttemptMs",
                            System.currentTimeMillis()
                                    + 5000L * Data.num(notice, "attemptCount", 1),
                            "_key",
                            "active:" + notice.get("assignmentId")));
            change(notice, "READY", "NOT_SENT");
        }
        Map<String, Object> person =
                store.lock(Store.Table.PERSON, Data.str(notice, "personId")).toBoundary();
        person.put("sendLeaseUntil", 0L);
        store.save(Store.Table.PERSON, person, Data.str(person, "status"));
        if (result.msgid() != null && !result.msgid().isBlank()) {
            reconcileEarlyCallback(result.msgid());
        }
        if ("mock".equals(wx.mode()) && "ACCEPTED".equals(state)) {
            store.create(
                    Store.Table.MOCK_CARD,
                    Data.map(
                            "notificationId",
                            notice.get("id"),
                            "assignmentId",
                            notice.get("assignmentId"),
                            "personId",
                            notice.get("personId"),
                            "title",
                            Data.obj(prepared.get("task")).get("title"),
                            "providerMsgId",
                            result.msgid(),
                            "deepLink",
                            "/#learner?assignmentId="
                                    + notice.get("assignmentId")
                                    + "&notificationId="
                                    + notice.get("id"),
                            "miniprogramPage",
                            page,
                            "providerMode",
                            "mock"),
                    Data.str(notice, "id"),
                    null,
                    "SENT",
                    result.msgid());
        }
    }

    private void reconcileEarlyCallback(String msgid) {
        for (StoredRecordDO stored : store.all(Store.Table.WECHAT_CALLBACK)) {
            Map<String, Object> callback = stored.toBoundary();
            if (!"UNMATCHED".equals(callback.get("matchStatus"))
                    || !"subscribe_msg_sent_event".equals(callback.get("eventType"))) {
                continue;
            }
            Map<String, Object> event = Data.obj(callback.get("payload"));
            Object nested = event.get("List");
            if (nested == null) {
                nested = Data.obj(event.get("SubscribeMsgSentEvent")).get("List");
            }
            List<Object> entries =
                    nested instanceof List<?>
                            ? Data.list(nested)
                            : List.of(nested == null ? Map.of() : nested);
            for (Object raw : entries) {
                Map<String, Object> entry = Data.obj(raw);
                if (msgid.equals(Data.str(entry, "MsgID"))) {
                    sentCallback(event, entry, callback);
                }
            }
            store.save(Store.Table.WECHAT_CALLBACK, callback, "PROCESSED");
        }
    }

    private void recoverUnknown(String id) {
        Map<String, Object> notice = store.lock(Store.Table.NOTIFICATION, id).toBoundary();
        if (!"SENDING".equals(notice.get("sendStatus"))
                || System.currentTimeMillis() - Data.num(notice, "startedMs", 0) <= SEND_LEASE_MS) {
            return;
        }
        notice.put("_key", null);
        change(notice, "UNKNOWN", "UNKNOWN");
        for (Map<String, Object> attempt :
                store.refs(Store.Table.NOTIFICATION_ATTEMPT, id).stream()
                        .map(StoredRecordDO::toBoundary)
                        .toList()) {
            if ("SENDING".equals(attempt.get("sendStatus"))) {
                attempt.putAll(Data.map("sendStatus", "UNKNOWN", "deliveryStatus", "UNKNOWN"));
                store.save(Store.Table.NOTIFICATION_ATTEMPT, attempt, "UNKNOWN");
            }
        }
    }

    private void change(Map<String, Object> notice, String sending, String delivery) {
        notice.putAll(Data.map("sendStatus", sending, "deliveryStatus", delivery));
        if ("CANCELED".equals(sending)) {
            notice.put("_key", null);
        }
        store.save(Store.Table.NOTIFICATION, notice, sending);
    }

    public void cancelPending(String assignmentId) {
        for (Map<String, Object> notice :
                store.refs(Store.Table.NOTIFICATION, assignmentId).stream()
                        .map(StoredRecordDO::toBoundary)
                        .toList()) {
            if (PENDING_STATES.contains(Data.str(notice, "sendStatus"))) {
                change(notice, "CANCELED", "NOT_SENT");
            }
        }
    }

    @Transactional
    public Map<String, Object> remind(
            String taskId, Map<String, Object> input, String key, String actorId) {
        Data.require(!key.isBlank(), "IDEMPOTENCY_REQUIRED", "Idempotency-Key required");
        Data.require(
                key.length() <= 128, "INVALID_INPUT", "Idempotency-Key exceeds 128 characters");
        List<Object> recipients = Data.list(input.get("personIds"));
        Data.require(
                !recipients.isEmpty() && recipients.size() <= 500,
                "INVALID_INPUT",
                "Select 1 to 500 people");
        Map<String, Object> task = store.lock(Store.Table.TASK, taskId).toBoundary();
        Map<String, Object> operations = Data.obj(task.get("reminderOperations"));
        String operationId = Data.hash(key);
        String requestHash = Data.contentHash(input);
        Map<String, Object> operation = Data.obj(operations.get(operationId));
        if (!operation.isEmpty()) {
            if (!requestHash.equals(operation.get("requestHash"))) {
                throw new BusinessException(
                        "IDEMPOTENCY_CONFLICT", "Key was used for different input", 409);
            }
            List<Map<String, Object>> existing =
                    Data.list(operation.get("notificationIds")).stream()
                            .map(
                                    value ->
                                            Store.publicView(
                                                    store.get(
                                                                    Store.Table.NOTIFICATION,
                                                                    value.toString())
                                                            .toBoundary()))
                            .toList();
            requestDispatch("all");
            return Data.map(
                    "notifications",
                    existing,
                    "queuedCount",
                    existing.size(),
                    "skippedCount",
                    Data.num(operation, "skippedCount", 0));
        }
        Data.require("ACTIVE".equals(task.get("status")), "TASK_NOT_ACTIVE", "Task is not active");
        List<Map<String, Object>> result = new ArrayList<>();
        long skipped = 0;
        for (Object raw : recipients.stream().distinct().toList()) {
            String personId = raw.toString();
            Map<String, Object> assignment =
                    store.byKey(Store.Table.ASSIGNMENT, taskId + ":" + personId).toBoundary();
            assignment =
                    store.lock(Store.Table.ASSIGNMENT, Data.str(assignment, "id")).toBoundary();
            if ("COMPLETED".equals(assignment.get("status"))) {
                skipped++;
                continue;
            }
            List<Map<String, Object>> previous =
                    store.refs(Store.Table.NOTIFICATION, Data.str(assignment, "id")).stream()
                            .map(StoredRecordDO::toBoundary)
                            .toList();
            Map<String, Object> same =
                    previous.stream()
                            .filter(row -> key.equals(row.get("operationKey")))
                            .findFirst()
                            .orElse(null);
            if (same != null) {
                result.add(Store.publicView(same));
                continue;
            }
            Map<String, Object> queued =
                    previous.stream()
                            .filter(
                                    row ->
                                            PENDING_STATES.contains(Data.str(row, "sendStatus"))
                                                    || "SENDING".equals(row.get("sendStatus")))
                            .findFirst()
                            .orElse(null);
            if (queued != null) {
                result.add(Store.publicView(queued));
                continue;
            }
            Map<String, Object> selectedHint = null;
            Map<String, Object> uncertain =
                    previous.stream()
                            .filter(
                                    row ->
                                            "UNKNOWN".equals(row.get("sendStatus"))
                                                    || ("ACCEPTED".equals(row.get("sendStatus"))
                                                            && "UNKNOWN"
                                                                    .equals(
                                                                            row.get(
                                                                                    "deliveryStatus"))))
                            .max(Comparator.comparingLong(row -> Data.num(row, "_createdMs", 0)))
                            .orElse(null);
            if (uncertain != null) {
                Data.require(
                        Data.bool(input, "confirmPossibleDuplicate"),
                        "UNKNOWN_REQUIRES_CONFIRMATION",
                        "New subscription and duplicate-risk confirmation required");
                String templateId =
                        Data.str(
                                store.get(Store.Table.APP_CONFIG, "notification").toBoundary(),
                                "templateId");
                Map<String, Object> currentBinding =
                        store.all(Store.Table.WX_IDENTITY_BINDING).stream()
                                .map(StoredRecordDO::toBoundary)
                                .filter(
                                        row ->
                                                personId.equals(row.get("personId"))
                                                        && "ACTIVE".equals(row.get("status")))
                                .findFirst()
                                .orElse(null);
                Data.require(
                        currentBinding != null,
                        "NEW_SUBSCRIPTION_REQUIRED",
                        "Active binding and fresh subscription required");
                long after = Data.num(uncertain, "startedMs", Data.num(uncertain, "_createdMs", 0));
                String requestedHint = Data.str(input, "newSubscriptionId");
                selectedHint =
                        store
                                .refs(
                                        Store.Table.SUBSCRIPTION_RECORD,
                                        Data.str(currentBinding, "id"))
                                .stream()
                                .map(StoredRecordDO::toBoundary)
                                .filter(
                                        row ->
                                                "accept".equals(row.get("result"))
                                                        && templateId.equals(row.get("templateId"))
                                                        && !Data.bool(row, "invalidated")
                                                        && Data.str(row, "hintUsedByNotificationId")
                                                                .isBlank()
                                                        && Data.num(row, "_createdMs", 0) > after
                                                        && (requestedHint.isBlank()
                                                                || requestedHint.equals(
                                                                        row.get("id"))))
                                .findFirst()
                                .orElse(null);
                Data.require(
                        selectedHint != null,
                        "NEW_SUBSCRIPTION_REQUIRED",
                        "Fresh subscription for this recipient is required");
            }
            Map<String, Object> created =
                    create(
                            assignment,
                            "MANUAL",
                            key,
                            uncertain == null ? null : Data.str(uncertain, "id"));
            if (selectedHint != null) {
                created.put("preferredSubscriptionId", selectedHint.get("id"));
                store.save(Store.Table.NOTIFICATION, created, Data.str(created, "sendStatus"));
            }
            result.add(Store.publicView(created));
        }
        operations.put(
                operationId,
                Data.map(
                        "requestHash",
                        requestHash,
                        "notificationIds",
                        result.stream().map(row -> row.get("id")).toList(),
                        "skippedCount",
                        skipped));
        task.put("reminderOperations", operations);
        store.save(Store.Table.TASK, task, Data.str(task, "status"));
        store.audit(
                actorId,
                "MANUAL_REMINDER",
                taskId,
                Data.map("reason", Data.required(input, "reason"), "count", result.size()));
        requestDispatch("all");
        return Data.map(
                "notifications", result, "queuedCount", result.size(), "skippedCount", skipped);
    }

    @Transactional
    public void callback(Map<String, Object> event, boolean simulated) {
        String type = Data.str(event, "Event");
        String key = Data.hash(type + Data.contentHash(event));
        Map<String, Object> existing =
                store.findKey(Store.Table.WECHAT_CALLBACK, key)
                        .map(StoredRecordDO::toBoundary)
                        .orElse(null);
        if (existing != null) {
            existing.put("receiveCount", Data.num(existing, "receiveCount", 1) + 1);
            store.save(
                    Store.Table.WECHAT_CALLBACK, existing, Data.str(existing, "processingState"));
            return;
        }
        Map<String, Object> callback =
                store.create(
                        Store.Table.WECHAT_CALLBACK,
                        Data.map(
                                "eventType",
                                type,
                                "payload",
                                event,
                                "simulated",
                                simulated,
                                "receiveCount",
                                1L,
                                "processingState",
                                "RECEIVED",
                                "matchStatus",
                                "UNMATCHED"),
                        null,
                        null,
                        "RECEIVED",
                        key);
        Object nested = event.get("List");
        if (nested == null) {
            String wrapper =
                    "subscribe_msg_sent_event".equals(type)
                            ? "SubscribeMsgSentEvent"
                            : "subscribe_msg_change_event".equals(type)
                                    ? "SubscribeMsgChangeEvent"
                                    : "SubscribeMsgPopupEvent";
            nested = Data.obj(event.get(wrapper)).get("List");
        }
        List<Object> entries =
                nested instanceof List
                        ? Data.list(nested)
                        : List.of(nested == null ? Map.of() : nested);
        for (Object raw : entries) {
            Map<String, Object> entry = Data.obj(raw);
            if ("subscribe_msg_sent_event".equals(type)) {
                sentCallback(event, entry, callback);
            } else {
                subscriptionCallback(event, entry, callback);
            }
        }
        callback.put("processingState", "PROCESSED");
        store.save(Store.Table.WECHAT_CALLBACK, callback, "PROCESSED");
        if (!"subscribe_msg_sent_event".equals(type)) {
            requestDispatch("all");
        }
    }

    private void sentCallback(
            Map<String, Object> event, Map<String, Object> entry, Map<String, Object> callback) {
        String msgid = Data.str(entry, "MsgID");
        if (msgid.isBlank()) {
            return;
        }
        List<Map<String, Object>> matches =
                store.all(Store.Table.NOTIFICATION_ATTEMPT).stream()
                        .map(StoredRecordDO::toBoundary)
                        .toList()
                        .stream()
                        .filter(row -> msgid.equals(row.get("providerMsgId")))
                        .toList();
        if (matches.size() != 1) {
            return;
        }
        Map<String, Object> attempt =
                store.lock(Store.Table.NOTIFICATION_ATTEMPT, Data.str(matches.get(0), "id"))
                        .toBoundary();
        Map<String, Object> template = Data.obj(attempt.get("templateSnapshot"));
        Map<String, Object> binding =
                store.get(Store.Table.WX_IDENTITY_BINDING, Data.str(attempt, "bindingId"))
                        .toBoundary();
        if (Data.str(entry, "TemplateId").isBlank()
                || !Data.str(entry, "TemplateId").equals(template.get("templateId"))
                || !auth.openid(binding).equals(Data.str(event, "FromUserName"))) {
            callback.put("matchStatus", "IDENTITY_OR_TEMPLATE_MISMATCH");
            return;
        }
        int code;
        try {
            code = Integer.parseInt(String.valueOf(entry.getOrDefault("ErrorCode", "-1")));
        } catch (NumberFormatException exception) {
            return;
        }
        String delivery = code == 0 ? "DELIVERED" : "DELIVERY_FAILED";
        if (!"UNKNOWN".equals(attempt.get("deliveryStatus"))
                && !delivery.equals(attempt.get("deliveryStatus"))) {
            callback.put("matchStatus", "CONFLICT");
            return;
        }
        attempt.putAll(
                Data.map(
                        "deliveryStatus",
                        delivery,
                        "deliveryErrcode",
                        code,
                        "callbackId",
                        callback.get("id")));
        store.save(Store.Table.NOTIFICATION_ATTEMPT, attempt, Data.str(attempt, "sendStatus"));
        Map<String, Object> notice =
                store.lock(Store.Table.NOTIFICATION, Data.str(attempt, "notificationId"))
                        .toBoundary();
        change(notice, Data.str(notice, "sendStatus"), delivery);
        callback.putAll(
                Data.map(
                        "matchedAttemptId",
                        attempt.get("id"),
                        "providerMsgId",
                        msgid,
                        "matchStatus",
                        "MATCHED"));
    }

    private void subscriptionCallback(
            Map<String, Object> event, Map<String, Object> entry, Map<String, Object> callback) {
        String identity = Data.hash(wx.appid() + ":" + Data.str(event, "FromUserName"));
        String template = Data.str(entry, "TemplateId");
        String result = Data.str(entry, "SubscribeStatusString");
        for (Map<String, Object> binding :
                store.refs(Store.Table.WX_IDENTITY_BINDING, identity).stream()
                        .map(StoredRecordDO::toBoundary)
                        .toList()) {
            if (!"ACTIVE".equals(binding.get("status"))) {
                continue;
            }
            if ("reject".equals(result)) {
                invalidate(binding, template);
                callback.put("matchStatus", "MATCHED");
            } else if ("accept".equals(result)) {
                List<Map<String, Object>> matches =
                        store
                                .refs(Store.Table.SUBSCRIPTION_RECORD, Data.str(binding, "id"))
                                .stream()
                                .map(StoredRecordDO::toBoundary)
                                .toList()
                                .stream()
                                .filter(
                                        row ->
                                                template.equals(row.get("templateId"))
                                                        && !Data.bool(row, "invalidated")
                                                        && Data.num(row, "expiresMs", 0)
                                                                >= System.currentTimeMillis())
                                .toList();
                if (matches.size() == 1) {
                    Map<String, Object> row = matches.get(0);
                    row.putAll(
                            Data.map(
                                    "result",
                                    "accept",
                                    "confidence",
                                    "PROVIDER_OBSERVED",
                                    "providerCallbackId",
                                    callback.get("id")));
                    store.save(Store.Table.SUBSCRIPTION_RECORD, row, "accept");
                    callback.put("matchStatus", "MATCHED");
                }
            }
        }
    }
}
