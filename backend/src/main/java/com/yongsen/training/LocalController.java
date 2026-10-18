package com.yongsen.training;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@Profile({"local", "test"})
@RequestMapping("/api/v1/local")
public class LocalController {
    private final Store store;
    private final AuthService auth;
    private final NotificationService notifications;

    public LocalController(Store store, AuthService auth, NotificationService notifications) {
        this.store = store;
        this.auth = auth;
        this.notifications = notifications;
    }

    @GetMapping("/outbox")
    public Object outbox(HttpServletRequest request) {
        auth.admin(request);
        return ApiController.page(
                store.all(Store.Table.MOCK_CARD).stream().map(StoredRecordDO::toBoundary).toList(),
                request);
    }

    @PostMapping("/notifications/{id}/simulate")
    public Object simulate(
            @PathVariable String id,
            @RequestBody Map<String, Object> input,
            HttpServletRequest request) {
        auth.admin(request);
        String outcome = Data.required(input, "outcome");
        Data.require(
                List.of("UNKNOWN", "FAILED").contains(outcome),
                "INVALID_OUTCOME",
                "Unsupported simulated outcome");
        Map<String, Object> notice = store.get(Store.Table.NOTIFICATION, id).toBoundary();
        Data.require(
                List.of("WAITING_BINDING", "WAITING_SUBSCRIPTION", "READY")
                        .contains(Data.str(notice, "sendStatus")),
                "ALREADY_SENT",
                "Fault injection must precede provider invocation");
        notice.put("simulateOutcome", outcome);
        store.save(Store.Table.NOTIFICATION, notice, Data.str(notice, "sendStatus"));
        return ApiAdvice.ok(Data.map("configuredOutcome", outcome));
    }

    @PostMapping("/notifications/{id}/deliver")
    public Object deliver(
            @PathVariable String id,
            @RequestBody Map<String, Object> input,
            HttpServletRequest request) {
        auth.admin(request);
        List<Map<String, Object>> attempts =
                store.refs(Store.Table.NOTIFICATION_ATTEMPT, id).stream()
                        .map(StoredRecordDO::toBoundary)
                        .toList();
        Data.require(!attempts.isEmpty(), "NOT_SENT", "No send attempt exists");
        Map<String, Object> attempt = attempts.get(attempts.size() - 1);
        Data.require(
                !Data.str(attempt, "providerMsgId").isBlank(),
                "NO_MSGID",
                "No reliable provider message ID");
        String result = Data.required(input, "result");
        Data.require(
                List.of("DELIVERED", "DELIVERY_FAILED").contains(result),
                "INVALID_RESULT",
                "Invalid result");
        Map<String, Object> binding =
                store.get(Store.Table.WX_IDENTITY_BINDING, Data.str(attempt, "bindingId"))
                        .toBoundary();
        Map<String, Object> event =
                Data.map(
                        "Event",
                        "subscribe_msg_sent_event",
                        "CreateTime",
                        0,
                        "FromUserName",
                        auth.openid(binding),
                        "SubscribeMsgSentEvent",
                        Data.map(
                                "List",
                                Data.map(
                                        "TemplateId",
                                        Data.obj(attempt.get("templateSnapshot")).get("templateId"),
                                        "MsgID",
                                        attempt.get("providerMsgId"),
                                        "ErrorCode",
                                        "DELIVERED".equals(result) ? 0 : 1)));
        notifications.callback(event, true);
        return ApiAdvice.ok(Data.map("recorded", true, "providerMode", "mock"));
    }
}
