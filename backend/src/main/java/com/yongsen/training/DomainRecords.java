package com.yongsen.training;

import java.sql.ResultSet;
import java.sql.SQLException;

/** Explicit typed persistence models. JSON appears only for structured payloads and snapshots. */
public final class DomainRecords {
    public interface DomainData {}

    private DomainRecords() {}

    public record AdminUserDO(String username, String passwordHash, String status)
            implements DomainData {}

    public record PersonDO(
            String personNo,
            String name,
            String unit,
            String project,
            String status,
            String inviteHash,
            Long inviteExpiresMs,
            Boolean inviteConsumed,
            String activeSessionId,
            Long sendLeaseUntil,
            String activeNotificationId)
            implements DomainData {}

    public record WxIdentityBindingDO(
            String personId,
            String personName,
            String identityId,
            String encryptedOpenid,
            String status,
            String inviteHash,
            String checkedAt,
            String checkedBy)
            implements DomainData {}

    public record MaterialDO(
            String title,
            String type,
            String status,
            String sha256,
            Long size,
            Long durationMs,
            Long pageCount,
            Object pages,
            Boolean compatibilityChecked,
            String filename,
            String objectKey,
            String error,
            String originalKey,
            String uploadHash,
            Long uploadExpiresMs,
            Boolean uploadInProgress,
            Boolean uploaded,
            String renderVersion,
            String reason,
            String compatibilityCheckedAt)
            implements DomainData {}

    public record TaskDO(
            String title,
            String description,
            String dueAt,
            String status,
            Object personIds,
            Object materials,
            String publishedAt,
            String publishKey,
            String publishRequestHash,
            String createdBy,
            String cancellationReason)
            implements DomainData {}

    public record TaskMaterialDO(
            String taskId,
            String materialId,
            String title,
            String type,
            Long durationMs,
            Long pageCount,
            Object pages,
            Object rule,
            Object materialSnapshot,
            String ruleHash)
            implements DomainData {}

    public record AssignmentDO(
            String taskId,
            String personId,
            String personName,
            Object personSnapshot,
            Object bindingSnapshot,
            String status,
            String firstEnteredAt,
            String firstLearningAt,
            String lastLearningAt,
            String confirmedAt,
            String completedAt,
            Object confirmationSnapshot,
            String confirmationHash,
            String confirmKey)
            implements DomainData {}

    public record LearningSessionDO(
            String personId,
            String assignmentId,
            String taskMaterialId,
            String bindingId,
            String state,
            Long expiresMs,
            Long startedMs,
            Long lastReceivedMs,
            Long receivedSeq,
            Long verifiedSeq,
            Object previousSample,
            Long baselineServerMs,
            Long pendingGapSince,
            Object speedWindow)
            implements DomainData {}

    public record LearningEventDO(
            String sessionId,
            String eventId,
            Long seq,
            String batchId,
            String type,
            Long elapsedMs,
            Object payload,
            String payloadHash,
            Long receivedMs,
            Boolean accepted,
            Boolean counted,
            String reason,
            String decision)
            implements DomainData {}

    public record LearningProgressDO(
            String assignmentId,
            String taskMaterialId,
            Long effectiveMs,
            Object coveredRanges,
            Object pageProgress,
            Boolean requirementsMet,
            Double coverageRatio,
            Object lastSample)
            implements DomainData {}

    public record NotificationDO(
            String assignmentId,
            String personId,
            String taskId,
            String kind,
            String operationKey,
            String sendStatus,
            String deliveryStatus,
            String openedAt,
            String previousNotificationId,
            Long nextAttemptMs,
            Long attemptCount,
            String simulateOutcome,
            String error,
            String providerMode,
            Long startedMs)
            implements DomainData {}

    public record NotificationAttemptDO(
            String notificationId,
            String bindingId,
            String subscriptionRecordId,
            Object templateSnapshot,
            Object payloadSnapshot,
            String sendStatus,
            String deliveryStatus,
            Long sendErrcode,
            Long deliveryErrcode,
            String providerMsgId,
            Long startedMs,
            String respondedAt,
            String callbackId)
            implements DomainData {}

    public record SubscriptionRecordDO(
            String bindingId,
            String identityId,
            String templateId,
            String nonceHash,
            Long expiresMs,
            String clientResult,
            String confidence,
            String result,
            String hintUsedByNotificationId,
            Boolean invalidated,
            String providerCallbackId)
            implements DomainData {}

    public record WechatCallbackDO(
            String eventType,
            String providerMsgId,
            String matchedAttemptId,
            String matchStatus,
            Long receiveCount,
            String processingState,
            Object payload)
            implements DomainData {}

    public record AuditLogDO(
            String actorId, String action, String targetId, String occurredAt, Object detail)
            implements DomainData {}

    public record AppConfigDO(String templateId, Object fieldMapping) implements DomainData {}

    public record AuthTokenDO(
            String role, String actorId, String encryptedOpenid, Long expiresMs, String status)
            implements DomainData {}

    public record MediaTicketDO(
            String materialId,
            Long pageNo,
            Long expiresMs,
            String ticketHash,
            String path,
            String purpose,
            String fileKey,
            String contentType)
            implements DomainData {}

    public record MockCardDO(
            String notificationId,
            String assignmentId,
            String personId,
            String title,
            String providerMsgId,
            String deepLink,
            String miniprogramPage,
            String providerMode)
            implements DomainData {}

    static DomainData read(Store.Table table, ResultSet row) throws SQLException {
        return switch (table) {
            case ADMIN_USER ->
                    new AdminUserDO(
                            row.getString("username"),
                            row.getString("password_hash"),
                            row.getString("status"));
            case PERSON ->
                    new PersonDO(
                            row.getString("person_no"),
                            row.getString("name"),
                            row.getString("unit"),
                            row.getString("project"),
                            row.getString("status"),
                            row.getString("invite_hash"),
                            row.getObject("invite_expires_ms", Long.class),
                            row.getObject("invite_consumed", Boolean.class),
                            row.getString("active_session_id"),
                            row.getObject("send_lease_until", Long.class),
                            row.getString("active_notification_id"));
            case WX_IDENTITY_BINDING ->
                    new WxIdentityBindingDO(
                            row.getString("person_id"),
                            row.getString("person_name"),
                            row.getString("identity_id"),
                            row.getString("encrypted_openid"),
                            row.getString("status"),
                            row.getString("invite_hash"),
                            instant(row, "checked_at"),
                            row.getString("checked_by"));
            case MATERIAL ->
                    new MaterialDO(
                            row.getString("title"),
                            row.getString("type"),
                            row.getString("status"),
                            row.getString("sha256"),
                            row.getObject("size", Long.class),
                            row.getObject("duration_ms", Long.class),
                            row.getObject("page_count", Long.class),
                            json(row.getString("pages")),
                            row.getObject("compatibility_checked", Boolean.class),
                            row.getString("filename"),
                            row.getString("object_key"),
                            row.getString("error"),
                            row.getString("original_key"),
                            row.getString("upload_hash"),
                            row.getObject("upload_expires_ms", Long.class),
                            row.getObject("upload_in_progress", Boolean.class),
                            row.getObject("uploaded", Boolean.class),
                            row.getString("render_version"),
                            row.getString("reason"),
                            instant(row, "compatibility_checked_at"));
            case TASK ->
                    new TaskDO(
                            row.getString("title"),
                            row.getString("description"),
                            instant(row, "due_at"),
                            row.getString("status"),
                            json(row.getString("person_ids")),
                            json(row.getString("materials")),
                            instant(row, "published_at"),
                            row.getString("publish_key"),
                            row.getString("publish_request_hash"),
                            row.getString("created_by"),
                            row.getString("cancellation_reason"));
            case TASK_MATERIAL ->
                    new TaskMaterialDO(
                            row.getString("task_id"),
                            row.getString("material_id"),
                            row.getString("title"),
                            row.getString("type"),
                            row.getObject("duration_ms", Long.class),
                            row.getObject("page_count", Long.class),
                            json(row.getString("pages")),
                            json(row.getString("rule")),
                            json(row.getString("material_snapshot")),
                            row.getString("rule_hash"));
            case ASSIGNMENT ->
                    new AssignmentDO(
                            row.getString("task_id"),
                            row.getString("person_id"),
                            row.getString("person_name"),
                            json(row.getString("person_snapshot")),
                            json(row.getString("binding_snapshot")),
                            row.getString("status"),
                            instant(row, "first_entered_at"),
                            instant(row, "first_learning_at"),
                            instant(row, "last_learning_at"),
                            instant(row, "confirmed_at"),
                            instant(row, "completed_at"),
                            json(row.getString("confirmation_snapshot")),
                            row.getString("confirmation_hash"),
                            row.getString("confirm_key"));
            case LEARNING_SESSION ->
                    new LearningSessionDO(
                            row.getString("person_id"),
                            row.getString("assignment_id"),
                            row.getString("task_material_id"),
                            row.getString("binding_id"),
                            row.getString("state"),
                            row.getObject("expires_ms", Long.class),
                            row.getObject("started_ms", Long.class),
                            row.getObject("last_received_ms", Long.class),
                            row.getObject("received_seq", Long.class),
                            row.getObject("verified_seq", Long.class),
                            json(row.getString("previous_sample")),
                            row.getObject("baseline_server_ms", Long.class),
                            row.getObject("pending_gap_since", Long.class),
                            json(row.getString("speed_window")));
            case LEARNING_EVENT ->
                    new LearningEventDO(
                            row.getString("session_id"),
                            row.getString("event_id"),
                            row.getObject("seq", Long.class),
                            row.getString("batch_id"),
                            row.getString("type"),
                            row.getObject("elapsed_ms", Long.class),
                            json(row.getString("payload")),
                            row.getString("payload_hash"),
                            row.getObject("received_ms", Long.class),
                            row.getObject("accepted", Boolean.class),
                            row.getObject("counted", Boolean.class),
                            row.getString("reason"),
                            row.getString("decision"));
            case LEARNING_PROGRESS ->
                    new LearningProgressDO(
                            row.getString("assignment_id"),
                            row.getString("task_material_id"),
                            row.getObject("effective_ms", Long.class),
                            json(row.getString("covered_ranges")),
                            json(row.getString("page_progress")),
                            row.getObject("requirements_met", Boolean.class),
                            row.getObject("coverage_ratio", Double.class),
                            json(row.getString("last_sample")));
            case NOTIFICATION ->
                    new NotificationDO(
                            row.getString("assignment_id"),
                            row.getString("person_id"),
                            row.getString("task_id"),
                            row.getString("kind"),
                            row.getString("operation_key"),
                            row.getString("send_status"),
                            row.getString("delivery_status"),
                            instant(row, "opened_at"),
                            row.getString("previous_notification_id"),
                            row.getObject("next_attempt_ms", Long.class),
                            row.getObject("attempt_count", Long.class),
                            row.getString("simulate_outcome"),
                            row.getString("error"),
                            row.getString("provider_mode"),
                            row.getObject("started_ms", Long.class));
            case NOTIFICATION_ATTEMPT ->
                    new NotificationAttemptDO(
                            row.getString("notification_id"),
                            row.getString("binding_id"),
                            row.getString("subscription_record_id"),
                            json(row.getString("template_snapshot")),
                            json(row.getString("payload_snapshot")),
                            row.getString("send_status"),
                            row.getString("delivery_status"),
                            row.getObject("send_errcode", Long.class),
                            row.getObject("delivery_errcode", Long.class),
                            row.getString("provider_msg_id"),
                            row.getObject("started_ms", Long.class),
                            instant(row, "responded_at"),
                            row.getString("callback_id"));
            case SUBSCRIPTION_RECORD ->
                    new SubscriptionRecordDO(
                            row.getString("binding_id"),
                            row.getString("identity_id"),
                            row.getString("template_id"),
                            row.getString("nonce_hash"),
                            row.getObject("expires_ms", Long.class),
                            row.getString("client_result"),
                            row.getString("confidence"),
                            row.getString("result"),
                            row.getString("hint_used_by_notification_id"),
                            row.getObject("invalidated", Boolean.class),
                            row.getString("provider_callback_id"));
            case WECHAT_CALLBACK ->
                    new WechatCallbackDO(
                            row.getString("event_type"),
                            row.getString("provider_msg_id"),
                            row.getString("matched_attempt_id"),
                            row.getString("match_status"),
                            row.getObject("receive_count", Long.class),
                            row.getString("processing_state"),
                            json(row.getString("payload")));
            case AUDIT_LOG ->
                    new AuditLogDO(
                            row.getString("actor_id"),
                            row.getString("action"),
                            row.getString("target_id"),
                            instant(row, "occurred_at"),
                            json(row.getString("detail")));
            case APP_CONFIG ->
                    new AppConfigDO(
                            row.getString("template_id"), json(row.getString("field_mapping")));
            case AUTH_TOKEN ->
                    new AuthTokenDO(
                            row.getString("role"),
                            row.getString("actor_id"),
                            row.getString("encrypted_openid"),
                            row.getObject("expires_ms", Long.class),
                            row.getString("status"));
            case MEDIA_TICKET ->
                    new MediaTicketDO(
                            row.getString("material_id"),
                            row.getObject("page_no", Long.class),
                            row.getObject("expires_ms", Long.class),
                            row.getString("ticket_hash"),
                            row.getString("path"),
                            row.getString("purpose"),
                            row.getString("file_key"),
                            row.getString("content_type"));
            case MOCK_CARD ->
                    new MockCardDO(
                            row.getString("notification_id"),
                            row.getString("assignment_id"),
                            row.getString("person_id"),
                            row.getString("title"),
                            row.getString("provider_msg_id"),
                            row.getString("deep_link"),
                            row.getString("miniprogram_page"),
                            row.getString("provider_mode"));
        };
    }

    private static String instant(ResultSet row, String column) throws SQLException {
        String value = row.getString(column);
        if (value == null || value.isBlank()) {
            return null;
        }
        // Older local databases used ISO text; new SQL schemas use UTC DATETIME(3).
        return value.contains("T")
                ? java.time.Instant.parse(value).toString()
                : row.getTimestamp(column).toInstant().toString();
    }

    private static Object json(String value) {
        return value == null ? null : Data.readAny(value);
    }
}
