package com.yongsen.training;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

class NotificationServiceTest {
    private BackendFixture fixture;
    private AuthService auth;
    private WxProvider wx;
    private NotificationService service;
    private Map<String, Object> actor;

    @BeforeEach
    void setup() {
        fixture = new BackendFixture();
        auth = mock(AuthService.class);
        wx = mock(WxProvider.class);
        when(auth.local()).thenReturn(true);
        when(auth.openid(anyMap())).thenReturn("openid-1");
        when(auth.binding(anyMap(), eq(true))).thenReturn(fixture.binding);
        when(wx.mode()).thenReturn("mock");
        when(wx.appid()).thenReturn("test-app");
        when(wx.templateConfigured(anyString()))
                .thenAnswer(invocation -> !((String) invocation.getArgument(0)).isBlank());
        when(wx.send(anyString(), anyString(), anyMap(), anyString()))
                .thenReturn(new WxProvider.SendResult(0, "ok", "msg-1", false));
        actor = Data.map("role", "LEARNER", "actorId", fixture.binding.get("identityId"));
        service = new NotificationService(fixture.store, auth, wx, fixture.transactions);
    }

    @Test
    void eventBatchDoesNotResendUnknownAndWaitingForAuthorizationDoesNotLoop() {
        Map<String, Object> notice = notice();
        NotificationService.DispatchBatch first = service.dispatchBatch("all", "");
        assertFalse(first.more());
        assertEquals("WAITING_SUBSCRIPTION", saved(notice).get("sendStatus"));
        accept();
        when(wx.send(anyString(), anyString(), anyMap(), anyString()))
                .thenReturn(new WxProvider.SendResult(-1, "Lost response", null, true));
        service.dispatchBatch("all", "");
        service.dispatchBatch("all", "");
        assertEquals("UNKNOWN", saved(notice).get("sendStatus"));
        assertEquals(1, fixture.store.all(Store.Table.NOTIFICATION_ATTEMPT).size());
        verify(wx, org.mockito.Mockito.times(1))
                .send(anyString(), anyString(), anyMap(), anyString());
    }

    @Test
    void eventBatchDefersActiveSendingAndRecoversExpiredSendAsUnknown() {
        Map<String, Object> notice = notice();
        notice.putAll(Data.map("sendStatus", "SENDING", "startedMs", System.currentTimeMillis()));
        fixture.store.save(Store.Table.NOTIFICATION, notice, "SENDING");
        assertEquals(
                "NOTIFICATION_BUSY",
                assertThrows(BusinessException.class, () -> service.dispatchBatch("all", ""))
                        .code());
        notice.put("startedMs", System.currentTimeMillis() - 65000L);
        fixture.store.save(Store.Table.NOTIFICATION, notice, "SENDING");
        service.dispatchBatch("all", "");
        assertEquals("UNKNOWN", saved(notice).get("sendStatus"));
        verify(wx, never()).send(anyString(), anyString(), anyMap(), anyString());
    }

    @Test
    void explicitBusyRetryIsDeferredToAnotherEvent() {
        Map<String, Object> notice = notice();
        accept();
        when(wx.send(anyString(), anyString(), anyMap(), anyString()))
                .thenReturn(new WxProvider.SendResult(-1, "System busy", null, false));
        assertEquals(
                "NOTIFICATION_RETRY",
                assertThrows(BusinessException.class, () -> service.dispatchBatch("all", ""))
                        .code());
        assertEquals("READY", saved(notice).get("sendStatus"));
        assertEquals("NOT_SENT", saved(notice).get("deliveryStatus"));
        assertEquals(1, fixture.store.all(Store.Table.NOTIFICATION_ATTEMPT).size());
        assertThrows(BusinessException.class, () -> service.dispatchBatch("all", ""));
        assertEquals(1, fixture.store.all(Store.Table.NOTIFICATION_ATTEMPT).size());
    }

    @Test
    void missingTemplateWaitsWithoutConsumingAuthorizationAndRecoversAfterConfiguration() {
        Map<String, Object> notice = notice();
        accept();
        Map<String, Object> config =
                fixture.store.get(Store.Table.APP_CONFIG, "notification").toBoundary();
        config.put("templateId", "");
        fixture.store.save(Store.Table.APP_CONFIG, config, "ACTIVE");
        service.dispatch();
        service.dispatch();
        assertEquals("WAITING_CONFIGURATION", saved(notice).get("sendStatus"));
        assertEquals("NOT_SENT", saved(notice).get("deliveryStatus"));
        assertEquals(0, fixture.store.all(Store.Table.NOTIFICATION_ATTEMPT).size());
        assertEquals(
                "",
                Data.str(
                        fixture.store.all(Store.Table.SUBSCRIPTION_RECORD).get(0).toBoundary(),
                        "hintUsedByNotificationId"));
        BusinessException error =
                assertThrows(BusinessException.class, () -> service.subscribe(actor));
        assertEquals("TEMPLATE_NOT_CONFIGURED", error.code());
        verify(wx, never()).send(anyString(), anyString(), anyMap(), anyString());
        config.put("templateId", "template-1");
        fixture.store.save(Store.Table.APP_CONFIG, config, "ACTIVE");
        service.dispatch();
        assertEquals("ACCEPTED", saved(notice).get("sendStatus"));
        assertEquals("UNKNOWN", saved(notice).get("deliveryStatus"));
        assertEquals(1, fixture.store.all(Store.Table.NOTIFICATION_ATTEMPT).size());
    }

    @Test
    void providerRejectedTemplateCannotBeSubscribedOrSent() {
        Map<String, Object> notice = notice();
        when(wx.templateConfigured("template-1")).thenReturn(false);
        assertEquals(
                "TEMPLATE_NOT_CONFIGURED",
                assertThrows(BusinessException.class, () -> service.subscribe(actor)).code());
        service.dispatch();
        assertEquals("WAITING_CONFIGURATION", saved(notice).get("sendStatus"));
        assertEquals(0, fixture.store.all(Store.Table.SUBSCRIPTION_RECORD).size());
        verify(wx, never()).send(anyString(), anyString(), anyMap(), anyString());
    }

    @Test
    void missingDeadlineWaitsWithoutConsumingHintAndRecoversWithATitleOnlyTemplate() {
        configure("template-1", Data.map("title", "thing1", "dueAt", "time2"));
        Map<String, Object> notice = notice();
        accept();
        StoredRecordDO originalHint = fixture.store.all(Store.Table.SUBSCRIPTION_RECORD).get(0);
        Map<String, Object> originalTask =
                fixture.store
                        .get(Store.Table.TASK, Data.str(fixture.assignment, "taskId"))
                        .toBoundary();
        service.dispatch();
        Map<String, Object> waiting = saved(notice);
        assertEquals("WAITING_CONFIGURATION", waiting.get("sendStatus"));
        assertEquals("NOT_SENT", waiting.get("deliveryStatus"));
        assertTrue(Data.str(waiting, "errorMessage").contains("截止时间"));
        service.dispatch();
        assertEquals(waiting.get("rowVersion"), saved(notice).get("rowVersion"));
        assertEquals(0, fixture.store.all(Store.Table.NOTIFICATION_ATTEMPT).size());
        assertEquals(
                originalHint,
                fixture.store.get(Store.Table.SUBSCRIPTION_RECORD, originalHint.id()));
        verify(wx, never()).send(anyString(), anyString(), anyMap(), anyString());

        configure("template-title-only", Data.map("title", "thing1"));
        service.dispatch();
        assertEquals("WAITING_SUBSCRIPTION", saved(notice).get("sendStatus"));
        assertFalse(saved(notice).containsKey("errorMessage"));
        assertEquals(0, fixture.store.all(Store.Table.NOTIFICATION_ATTEMPT).size());
        accept();
        service.dispatch();
        assertEquals("ACCEPTED", saved(notice).get("sendStatus"));
        assertFalse(saved(notice).containsKey("errorMessage"));
        assertEquals(1, fixture.store.all(Store.Table.NOTIFICATION_ATTEMPT).size());
        assertEquals(
                originalHint,
                fixture.store.get(Store.Table.SUBSCRIPTION_RECORD, originalHint.id()));
        assertEquals(
                originalTask,
                fixture.store
                        .get(Store.Table.TASK, Data.str(fixture.assignment, "taskId"))
                        .toBoundary());
        verify(wx)
                .send(
                        anyString(),
                        eq("template-title-only"),
                        eq(Data.map("thing1", Data.map("value", "测试任务"))),
                        anyString());
    }

    @Test
    void publicationRejectsMissingDeadlineBeforeFreezingAnyBusinessRows() {
        configure("template-1", Data.map("title", "thing1", "dueAt", "time2"));
        TaskService tasks = new TaskService(fixture.store, auth, service);
        Map<String, Object> task = draft(tasks, null);
        String taskId = Data.str(task, "id");
        BusinessException error =
                assertThrows(
                        BusinessException.class,
                        () ->
                                fixture.transactions.execute(
                                        status ->
                                                tasks.publish(
                                                        taskId, "publish-deadline", "admin")));
        assertEquals("NOTIFICATION_DEADLINE_REQUIRED", error.code());
        assertTrue(error.getMessage().contains("填写培训完成截止时间"));
        assertEquals(
                "DRAFT", fixture.store.get(Store.Table.TASK, taskId).toBoundary().get("status"));
        assertTrue(fixture.store.refs(Store.Table.TASK_MATERIAL, taskId).isEmpty());
        assertTrue(fixture.store.refs(Store.Table.ASSIGNMENT, taskId).isEmpty());
        assertTrue(fixture.store.all(Store.Table.NOTIFICATION).isEmpty());
    }

    @Test
    void configuredDeadlineTemplateAllowsPublicationWhenDeadlineIsPresent() {
        configure("template-1", Data.map("title", "thing1", "dueAt", "time2"));
        TaskService tasks = new TaskService(fixture.store, auth, service);
        Map<String, Object> task = draft(tasks, "2026-10-18T09:00:00Z");
        String taskId = Data.str(task, "id");
        fixture.transactions.executeWithoutResult(
                status -> tasks.publish(taskId, "publish-with-deadline", "admin"));
        assertEquals(
                "ACTIVE", fixture.store.get(Store.Table.TASK, taskId).toBoundary().get("status"));
        assertEquals(1, fixture.store.refs(Store.Table.ASSIGNMENT, taskId).size());
        accept();
        service.dispatch();
        verify(wx)
                .send(
                        anyString(),
                        eq("template-1"),
                        eq(
                                Data.map(
                                        "thing1",
                                        Data.map("value", "待发布培训"),
                                        "time2",
                                        Data.map("value", "2026-10-18 17:00"))),
                        anyString());
    }

    @Test
    void missingOrRejectedTemplateDoesNotBlockPublishingWithoutDeadline() {
        TaskService tasks = new TaskService(fixture.store, auth, service);
        when(wx.templateConfigured("mock-old-template")).thenReturn(false);
        for (String template : List.of("", "mock-old-template")) {
            configure(template, Data.map("title", "thing1", "dueAt", "time2"));
            Map<String, Object> task = draft(tasks, null);
            String taskId = Data.str(task, "id");
            fixture.transactions.executeWithoutResult(
                    status -> tasks.publish(taskId, "publish-" + taskId, "admin"));
            assertEquals(
                    "ACTIVE",
                    fixture.store.get(Store.Table.TASK, taskId).toBoundary().get("status"));
            assertEquals(1, fixture.store.refs(Store.Table.ASSIGNMENT, taskId).size());
        }
    }

    @Test
    void unknownOutcomePersistsAttemptAndCannotBeBlindlySentAgain() {
        Map<String, Object> notice = notice();
        notice.put("simulateOutcome", "UNKNOWN");
        fixture.store.save(Store.Table.NOTIFICATION, notice, "WAITING_BINDING");
        accept();
        service.dispatch();
        service.dispatch();
        assertEquals("UNKNOWN", saved(notice).get("sendStatus"));
        assertEquals(
                1,
                fixture.store
                        .refs(Store.Table.NOTIFICATION_ATTEMPT, Data.str(notice, "id"))
                        .size());
        verify(wx, never()).send(anyString(), anyString(), anyMap(), anyString());
        assertThrows(
                BusinessException.class,
                () ->
                        service.remind(
                                Data.str(fixture.assignment, "taskId"),
                                Data.map(
                                        "personIds",
                                        java.util.List.of(fixture.person.get("id")),
                                        "reason",
                                        "跟进"),
                                "again",
                                "admin"));
    }

    @Test
    void deliveryRequiresMessageIdentityAndTemplateAndRepeatedCallbackIsIdempotent() {
        Map<String, Object> notice = notice();
        accept();
        service.dispatch();
        assertEquals("ACCEPTED", saved(notice).get("sendStatus"));
        assertEquals("UNKNOWN", saved(notice).get("deliveryStatus"));
        service.callback(sent("wrong-person", "template-1", "msg-1"), true);
        service.callback(sent("openid-1", "wrong-template", "msg-1"), true);
        service.callback(sent("openid-1", "template-1", ""), true);
        assertEquals("UNKNOWN", saved(notice).get("deliveryStatus"));
        Map<String, Object> callback = sent("openid-1", "template-1", "msg-1");
        service.callback(callback, true);
        service.callback(callback, true);
        assertEquals("DELIVERED", saved(notice).get("deliveryStatus"));
        assertEquals("ACCEPTED", saved(notice).get("sendStatus"));
        long duplicateCount =
                fixture.store.all(Store.Table.WECHAT_CALLBACK).stream()
                        .map(StoredRecordDO::toBoundary)
                        .filter(row -> "MATCHED".equals(row.get("matchStatus")))
                        .mapToLong(row -> Data.num(row, "receiveCount", 0))
                        .sum();
        assertEquals(2, duplicateCount);
    }

    @Test
    void callbackBeforeHttpResponseIsReconciledAfterResponsePersistsMessageId() {
        Map<String, Object> notice = notice();
        accept();
        when(wx.send(anyString(), anyString(), anyMap(), anyString()))
                .thenAnswer(
                        invocation -> {
                            service.callback(sent("openid-1", "template-1", "msg-early"), true);
                            return new WxProvider.SendResult(0, "ok", "msg-early", false);
                        });
        service.dispatch();
        assertEquals("DELIVERED", saved(notice).get("deliveryStatus"));
    }

    @Test
    void providerObservedAuthorizationCanArriveBeforeClientResultWithoutCreatingTwoHints() {
        Map<String, Object> request = service.subscribe(actor);
        service.callback(
                Data.map(
                        "Event",
                        "subscribe_msg_popup_event",
                        "FromUserName",
                        "openid-1",
                        "List",
                        Data.map("TemplateId", "template-1", "SubscribeStatusString", "accept")),
                true);
        service.subscriptionResult(
                Data.str(request, "id"),
                actor,
                Data.map("nonce", request.get("nonce"), "result", "accept"));
        assertEquals(1, fixture.store.all(Store.Table.SUBSCRIPTION_RECORD).size());
        assertEquals(
                "PROVIDER_OBSERVED",
                fixture.store
                        .get(Store.Table.SUBSCRIPTION_RECORD, Data.str(request, "id"))
                        .toBoundary()
                        .get("confidence"));
    }

    @Test
    void freshAuthorizationIsSelectedWithoutExposingInternalIdToAdministrator() {
        Map<String, Object> first = notice();
        first.put("simulateOutcome", "UNKNOWN");
        fixture.store.save(Store.Table.NOTIFICATION, first, "WAITING_BINDING");
        accept();
        service.dispatch();
        accept();
        Map<String, Object> result =
                service.remind(
                        Data.str(fixture.assignment, "taskId"),
                        Data.map(
                                "personIds",
                                java.util.List.of(fixture.person.get("id")),
                                "reason",
                                "跟进",
                                "confirmPossibleDuplicate",
                                true),
                        "manual-new",
                        "admin");
        Map<String, Object> next = Data.obj(Data.list(result.get("notifications")).get(0));
        service.dispatch();
        assertEquals("UNKNOWN", saved(first).get("sendStatus"));
        assertEquals("ACCEPTED", saved(next).get("sendStatus"));
        assertEquals(first.get("id"), saved(next).get("previousNotificationId"));
    }

    @Test
    void reminderKeyRejectsDifferentContentAndRetriesReturnOriginalPendingIntent() {
        Map<String, Object> first = notice();
        Map<String, Object> input =
                Data.map("personIds", java.util.List.of(fixture.person.get("id")), "reason", "跟进");
        Map<String, Object> firstResult =
                service.remind(Data.str(fixture.assignment, "taskId"), input, "manual-1", "admin");
        Map<String, Object> repeated =
                service.remind(
                        Data.str(fixture.assignment, "taskId"),
                        Data.map(
                                "reason",
                                "跟进",
                                "personIds",
                                java.util.List.of(fixture.person.get("id"))),
                        "manual-1",
                        "admin");
        assertEquals(firstResult, repeated);
        assertEquals(
                first.get("id"),
                Data.obj(Data.list(repeated.get("notifications")).get(0)).get("id"));
        BusinessException error =
                assertThrows(
                        BusinessException.class,
                        () ->
                                service.remind(
                                        Data.str(fixture.assignment, "taskId"),
                                        Data.map(
                                                "personIds",
                                                java.util.List.of(fixture.person.get("id")),
                                                "reason",
                                                "不同内容"),
                                        "manual-1",
                                        "admin"));
        assertEquals("IDEMPOTENCY_CONFLICT", error.code());
    }

    private void configure(String template, Map<String, Object> mapping) {
        Map<String, Object> config =
                fixture.store.get(Store.Table.APP_CONFIG, "notification").toBoundary();
        config.put("templateId", template);
        config.put("fieldMapping", mapping);
        fixture.store.save(Store.Table.APP_CONFIG, config, "ACTIVE");
    }

    private Map<String, Object> draft(TaskService tasks, String dueAt) {
        Map<String, Object> material =
                fixture.store.create(
                        Store.Table.MATERIAL,
                        Data.map(
                                "title",
                                "培训视频",
                                "type",
                                "MP4",
                                "durationMs",
                                12000L,
                                "status",
                                "READY",
                                "compatibilityChecked",
                                true),
                        null,
                        null,
                        "READY",
                        null);
        return tasks.create(
                Data.map(
                        "title",
                        "待发布培训",
                        "personIds",
                        List.of(fixture.person.get("id")),
                        "materials",
                        List.of(Data.map("materialId", material.get("id"))),
                        "dueAt",
                        dueAt),
                "admin");
    }

    private Map<String, Object> notice() {
        return service.create(fixture.assignment, "ASSIGNMENT", "publish", null);
    }

    private void accept() {
        Map<String, Object> request = service.subscribe(actor);
        service.subscriptionResult(
                Data.str(request, "id"),
                actor,
                Data.map("nonce", request.get("nonce"), "result", "accept"));
    }

    private Map<String, Object> saved(Map<String, Object> notice) {
        return fixture.store.get(Store.Table.NOTIFICATION, Data.str(notice, "id")).toBoundary();
    }

    private Map<String, Object> sent(String openid, String template, String msgid) {
        return Data.map(
                "Event",
                "subscribe_msg_sent_event",
                "FromUserName",
                openid,
                "SubscribeMsgSentEvent",
                Data.map("List", Data.map("TemplateId", template, "MsgID", msgid, "ErrorCode", 0)));
    }
}
