package com.yongsen.training;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** Fixed per-domain column allowlist. Never derived from request field names. */
final class RecordColumns {
    record Column(String field, String kind) {
        String sqlName() {
            return field.replaceAll("([a-z])([A-Z])", "$1_$2").toLowerCase();
        }
    }

    private static final Map<Store.Table, String> COLUMNS = definitions();

    private RecordColumns() {}

    private static Map<Store.Table, String> definitions() {
        Map<Store.Table, String> columns = new EnumMap<>(Store.Table.class);
        columns.put(Store.Table.ADMIN_USER, "username:S passwordHash:S status:S");
        columns.put(
                Store.Table.PERSON,
                "personNo:S name:S unit:S project:S status:S inviteHash:S inviteExpiresMs:L"
                        + " inviteConsumed:B activeSessionId:S sendLeaseUntil:L"
                        + " activeNotificationId:S");
        columns.put(
                Store.Table.WX_IDENTITY_BINDING,
                "personId:S personName:S identityId:S encryptedOpenid:T status:S inviteHash:S"
                        + " checkedAt:I checkedBy:S");
        columns.put(
                Store.Table.MATERIAL,
                "title:S type:S status:S sha256:S size:L durationMs:L pageCount:L pages:J"
                        + " compatibilityChecked:B filename:S objectKey:S error:T originalKey:S"
                        + " uploadHash:S uploadExpiresMs:L uploadInProgress:B uploaded:B"
                        + " renderVersion:S reason:T compatibilityCheckedAt:I");
        columns.put(
                Store.Table.TASK,
                "title:S description:T dueAt:I status:S personIds:J materials:J publishedAt:I"
                        + " publishKey:S publishRequestHash:S createdBy:S cancellationReason:T");
        columns.put(
                Store.Table.TASK_MATERIAL,
                "taskId:S materialId:S title:S type:S durationMs:L pageCount:L pages:J rule:J"
                        + " materialSnapshot:J ruleHash:S");
        columns.put(
                Store.Table.ASSIGNMENT,
                "taskId:S personId:S personName:S personSnapshot:J bindingSnapshot:J status:S"
                        + " firstEnteredAt:I firstLearningAt:I lastLearningAt:I confirmedAt:I"
                        + " completedAt:I confirmationSnapshot:J confirmationHash:S confirmKey:S");
        columns.put(
                Store.Table.LEARNING_SESSION,
                "personId:S assignmentId:S taskMaterialId:S bindingId:S state:S expiresMs:L"
                        + " startedMs:L lastReceivedMs:L receivedSeq:L verifiedSeq:L previousSample:J"
                        + " baselineServerMs:L pendingGapSince:L speedWindow:J");
        columns.put(
                Store.Table.LEARNING_EVENT,
                "sessionId:S eventId:S seq:L batchId:S type:S elapsedMs:L payload:J payloadHash:S"
                        + " receivedMs:L accepted:B counted:B reason:S decision:S");
        columns.put(
                Store.Table.LEARNING_PROGRESS,
                "assignmentId:S taskMaterialId:S effectiveMs:L coveredRanges:J pageProgress:J"
                        + " requirementsMet:B coverageRatio:D lastSample:J");
        columns.put(
                Store.Table.NOTIFICATION,
                "assignmentId:S personId:S taskId:S kind:S operationKey:S sendStatus:S"
                        + " deliveryStatus:S openedAt:I previousNotificationId:S nextAttemptMs:L"
                        + " attemptCount:L simulateOutcome:S error:S providerMode:S startedMs:L");
        columns.put(
                Store.Table.NOTIFICATION_ATTEMPT,
                "notificationId:S bindingId:S subscriptionRecordId:S templateSnapshot:J"
                        + " payloadSnapshot:J sendStatus:S deliveryStatus:S sendErrcode:L"
                        + " deliveryErrcode:L providerMsgId:S startedMs:L respondedAt:I callbackId:S");
        columns.put(
                Store.Table.SUBSCRIPTION_RECORD,
                "bindingId:S identityId:S templateId:S nonceHash:S expiresMs:L clientResult:S"
                        + " confidence:S result:S hintUsedByNotificationId:S invalidated:B"
                        + " providerCallbackId:S");
        columns.put(
                Store.Table.WECHAT_CALLBACK,
                "eventType:S providerMsgId:S matchedAttemptId:S matchStatus:S receiveCount:L"
                        + " processingState:S payload:J");
        columns.put(Store.Table.AUDIT_LOG, "actorId:S action:S targetId:S occurredAt:I detail:J");
        columns.put(Store.Table.APP_CONFIG, "templateId:S fieldMapping:J");
        columns.put(
                Store.Table.AUTH_TOKEN, "role:S actorId:S encryptedOpenid:T expiresMs:L status:S");
        columns.put(
                Store.Table.MEDIA_TICKET,
                "materialId:S pageNo:L expiresMs:L ticketHash:S path:T purpose:S fileKey:S"
                        + " contentType:S");
        columns.put(
                Store.Table.MOCK_CARD,
                "notificationId:S assignmentId:S personId:S title:S providerMsgId:S deepLink:T"
                        + " miniprogramPage:T providerMode:S");
        return Map.copyOf(columns);
    }

    static List<Column> forTable(Store.Table table) {
        return Arrays.stream(COLUMNS.get(table).split(" "))
                .map(
                        item -> {
                            String[] parts = item.split(":");
                            return new Column(parts[0], parts[1]);
                        })
                .toList();
    }
}
