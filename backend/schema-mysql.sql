-- Domain columns are authoritative; JSON columns store structured snapshots/payload only.
CREATE TABLE IF NOT EXISTS admin_user (
    id VARCHAR(64) PRIMARY KEY COMMENT 'Server generated identifier',
    ref_id VARCHAR(128) COMMENT 'Indexed owning aggregate',
    ref2 VARCHAR(128) COMMENT 'Secondary relationship',
    record_status VARCHAR(64) COMMENT 'Queue or lifecycle selector',
    unique_key VARCHAR(255) UNIQUE COMMENT 'Business idempotency key',
    snapshot_json LONGTEXT NOT NULL COMMENT 'Additional immutable snapshot metadata',
    row_version BIGINT NOT NULL DEFAULT 1 COMMENT 'Optimistic revision',
    created_at DATETIME(3) NOT NULL COMMENT 'UTC creation time',
    updated_at DATETIME(3) NOT NULL COMMENT 'UTC update time',
    username VARCHAR(128) COMMENT 'username',
    password_hash VARCHAR(64) COMMENT 'passwordHash',
    status VARCHAR(64) COMMENT 'status'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT 'admin_user domain data';
CREATE INDEX idx_admin_user_ref ON admin_user(ref_id, ref2);
CREATE INDEX idx_admin_user_status ON admin_user(record_status, created_at);
CREATE TABLE IF NOT EXISTS person (
    id VARCHAR(64) PRIMARY KEY COMMENT 'Server generated identifier',
    ref_id VARCHAR(128) COMMENT 'Indexed owning aggregate',
    ref2 VARCHAR(128) COMMENT 'Secondary relationship',
    record_status VARCHAR(64) COMMENT 'Queue or lifecycle selector',
    unique_key VARCHAR(255) UNIQUE COMMENT 'Business idempotency key',
    snapshot_json LONGTEXT NOT NULL COMMENT 'Additional immutable snapshot metadata',
    row_version BIGINT NOT NULL DEFAULT 1 COMMENT 'Optimistic revision',
    created_at DATETIME(3) NOT NULL COMMENT 'UTC creation time',
    updated_at DATETIME(3) NOT NULL COMMENT 'UTC update time',
    person_no VARCHAR(128) COMMENT 'personNo',
    name VARCHAR(512) COMMENT 'name',
    unit VARCHAR(512) COMMENT 'unit',
    project VARCHAR(512) COMMENT 'project',
    status VARCHAR(64) COMMENT 'status',
    invite_hash VARCHAR(64) COMMENT 'inviteHash',
    invite_expires_ms BIGINT COMMENT 'inviteExpiresMs',
    invite_consumed BOOLEAN COMMENT 'inviteConsumed',
    active_session_id VARCHAR(128) COMMENT 'activeSessionId',
    send_lease_until BIGINT COMMENT 'sendLeaseUntil',
    active_notification_id VARCHAR(128) COMMENT 'activeNotificationId',
    UNIQUE (person_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT 'person domain data';
CREATE INDEX idx_person_ref ON person(ref_id, ref2);
CREATE INDEX idx_person_status ON person(record_status, created_at);
CREATE TABLE IF NOT EXISTS wx_identity_binding (
    id VARCHAR(64) PRIMARY KEY COMMENT 'Server generated identifier',
    ref_id VARCHAR(128) COMMENT 'Indexed owning aggregate',
    ref2 VARCHAR(128) COMMENT 'Secondary relationship',
    record_status VARCHAR(64) COMMENT 'Queue or lifecycle selector',
    unique_key VARCHAR(255) UNIQUE COMMENT 'Business idempotency key',
    snapshot_json LONGTEXT NOT NULL COMMENT 'Additional immutable snapshot metadata',
    row_version BIGINT NOT NULL DEFAULT 1 COMMENT 'Optimistic revision',
    created_at DATETIME(3) NOT NULL COMMENT 'UTC creation time',
    updated_at DATETIME(3) NOT NULL COMMENT 'UTC update time',
    person_id VARCHAR(128) COMMENT 'personId',
    person_name VARCHAR(512) COMMENT 'personName',
    identity_id VARCHAR(128) COMMENT 'identityId',
    encrypted_openid LONGTEXT COMMENT 'encryptedOpenid',
    status VARCHAR(64) COMMENT 'status',
    invite_hash VARCHAR(64) COMMENT 'inviteHash',
    checked_at DATETIME(3) COMMENT 'checkedAt',
    checked_by VARCHAR(128) COMMENT 'checkedBy',
    active_identity_key VARCHAR(128) UNIQUE COMMENT 'One active or pending binding per WeChat identity'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT 'wx_identity_binding domain data';
CREATE INDEX idx_wx_identity_binding_ref ON wx_identity_binding(ref_id, ref2);
CREATE INDEX idx_wx_identity_binding_status ON wx_identity_binding(record_status, created_at);
CREATE TABLE IF NOT EXISTS material (
    id VARCHAR(64) PRIMARY KEY COMMENT 'Server generated identifier',
    ref_id VARCHAR(128) COMMENT 'Indexed owning aggregate',
    ref2 VARCHAR(128) COMMENT 'Secondary relationship',
    record_status VARCHAR(64) COMMENT 'Queue or lifecycle selector',
    unique_key VARCHAR(255) UNIQUE COMMENT 'Business idempotency key',
    snapshot_json LONGTEXT NOT NULL COMMENT 'Additional immutable snapshot metadata',
    row_version BIGINT NOT NULL DEFAULT 1 COMMENT 'Optimistic revision',
    created_at DATETIME(3) NOT NULL COMMENT 'UTC creation time',
    updated_at DATETIME(3) NOT NULL COMMENT 'UTC update time',
    title VARCHAR(512) COMMENT 'title',
    type VARCHAR(64) COMMENT 'type',
    status VARCHAR(64) COMMENT 'status',
    sha256 VARCHAR(512) COMMENT 'sha256',
    size BIGINT COMMENT 'size',
    duration_ms BIGINT COMMENT 'durationMs',
    page_count BIGINT COMMENT 'pageCount',
    pages LONGTEXT COMMENT 'pages',
    compatibility_checked BOOLEAN COMMENT 'compatibilityChecked',
    filename VARCHAR(512) COMMENT 'filename',
    object_key VARCHAR(255) COMMENT 'objectKey',
    error LONGTEXT COMMENT 'error',
    original_key VARCHAR(255) COMMENT 'originalKey',
    upload_hash VARCHAR(64) COMMENT 'uploadHash',
    upload_expires_ms BIGINT COMMENT 'uploadExpiresMs',
    upload_in_progress BOOLEAN COMMENT 'uploadInProgress',
    uploaded BOOLEAN COMMENT 'uploaded',
    render_version VARCHAR(512) COMMENT 'renderVersion',
    reason LONGTEXT COMMENT 'reason',
    compatibility_checked_at DATETIME(3) COMMENT 'compatibilityCheckedAt'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT 'material domain data';
CREATE INDEX idx_material_ref ON material(ref_id, ref2);
CREATE INDEX idx_material_status ON material(record_status, created_at);
CREATE TABLE IF NOT EXISTS task (
    id VARCHAR(64) PRIMARY KEY COMMENT 'Server generated identifier',
    ref_id VARCHAR(128) COMMENT 'Indexed owning aggregate',
    ref2 VARCHAR(128) COMMENT 'Secondary relationship',
    record_status VARCHAR(64) COMMENT 'Queue or lifecycle selector',
    unique_key VARCHAR(255) UNIQUE COMMENT 'Business idempotency key',
    snapshot_json LONGTEXT NOT NULL COMMENT 'Additional immutable snapshot metadata',
    row_version BIGINT NOT NULL DEFAULT 1 COMMENT 'Optimistic revision',
    created_at DATETIME(3) NOT NULL COMMENT 'UTC creation time',
    updated_at DATETIME(3) NOT NULL COMMENT 'UTC update time',
    title VARCHAR(512) COMMENT 'title',
    description LONGTEXT COMMENT 'description',
    due_at DATETIME(3) COMMENT 'dueAt',
    status VARCHAR(64) COMMENT 'status',
    person_ids LONGTEXT COMMENT 'personIds',
    materials LONGTEXT COMMENT 'materials',
    published_at DATETIME(3) COMMENT 'publishedAt',
    publish_key VARCHAR(255) COMMENT 'publishKey',
    publish_request_hash VARCHAR(64) COMMENT 'publishRequestHash',
    created_by VARCHAR(128) COMMENT 'createdBy',
    cancellation_reason LONGTEXT COMMENT 'cancellationReason'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT 'task domain data';
CREATE INDEX idx_task_ref ON task(ref_id, ref2);
CREATE INDEX idx_task_status ON task(record_status, created_at);
CREATE TABLE IF NOT EXISTS task_material (
    id VARCHAR(64) PRIMARY KEY COMMENT 'Server generated identifier',
    ref_id VARCHAR(128) COMMENT 'Indexed owning aggregate',
    ref2 VARCHAR(128) COMMENT 'Secondary relationship',
    record_status VARCHAR(64) COMMENT 'Queue or lifecycle selector',
    unique_key VARCHAR(255) UNIQUE COMMENT 'Business idempotency key',
    snapshot_json LONGTEXT NOT NULL COMMENT 'Additional immutable snapshot metadata',
    row_version BIGINT NOT NULL DEFAULT 1 COMMENT 'Optimistic revision',
    created_at DATETIME(3) NOT NULL COMMENT 'UTC creation time',
    updated_at DATETIME(3) NOT NULL COMMENT 'UTC update time',
    task_id VARCHAR(128) COMMENT 'taskId',
    material_id VARCHAR(128) COMMENT 'materialId',
    title VARCHAR(512) COMMENT 'title',
    type VARCHAR(64) COMMENT 'type',
    duration_ms BIGINT COMMENT 'durationMs',
    page_count BIGINT COMMENT 'pageCount',
    pages LONGTEXT COMMENT 'pages',
    rule LONGTEXT COMMENT 'rule',
    material_snapshot LONGTEXT COMMENT 'materialSnapshot',
    rule_hash VARCHAR(64) COMMENT 'ruleHash'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT 'task_material domain data';
CREATE INDEX idx_task_material_ref ON task_material(ref_id, ref2);
CREATE INDEX idx_task_material_status ON task_material(record_status, created_at);
CREATE TABLE IF NOT EXISTS assignment (
    id VARCHAR(64) PRIMARY KEY COMMENT 'Server generated identifier',
    ref_id VARCHAR(128) COMMENT 'Indexed owning aggregate',
    ref2 VARCHAR(128) COMMENT 'Secondary relationship',
    record_status VARCHAR(64) COMMENT 'Queue or lifecycle selector',
    unique_key VARCHAR(255) UNIQUE COMMENT 'Business idempotency key',
    snapshot_json LONGTEXT NOT NULL COMMENT 'Additional immutable snapshot metadata',
    row_version BIGINT NOT NULL DEFAULT 1 COMMENT 'Optimistic revision',
    created_at DATETIME(3) NOT NULL COMMENT 'UTC creation time',
    updated_at DATETIME(3) NOT NULL COMMENT 'UTC update time',
    task_id VARCHAR(128) COMMENT 'taskId',
    person_id VARCHAR(128) COMMENT 'personId',
    person_name VARCHAR(512) COMMENT 'personName',
    person_snapshot LONGTEXT COMMENT 'personSnapshot',
    binding_snapshot LONGTEXT COMMENT 'bindingSnapshot',
    status VARCHAR(64) COMMENT 'status',
    first_entered_at DATETIME(3) COMMENT 'firstEnteredAt',
    first_learning_at DATETIME(3) COMMENT 'firstLearningAt',
    last_learning_at DATETIME(3) COMMENT 'lastLearningAt',
    confirmed_at DATETIME(3) COMMENT 'confirmedAt',
    completed_at DATETIME(3) COMMENT 'completedAt',
    confirmation_snapshot LONGTEXT COMMENT 'confirmationSnapshot',
    confirmation_hash VARCHAR(64) COMMENT 'confirmationHash',
    confirm_key VARCHAR(255) COMMENT 'confirmKey',
    UNIQUE (task_id, person_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT 'assignment domain data';
CREATE INDEX idx_assignment_ref ON assignment(ref_id, ref2);
CREATE INDEX idx_assignment_status ON assignment(record_status, created_at);
CREATE TABLE IF NOT EXISTS learning_session (
    id VARCHAR(64) PRIMARY KEY COMMENT 'Server generated identifier',
    ref_id VARCHAR(128) COMMENT 'Indexed owning aggregate',
    ref2 VARCHAR(128) COMMENT 'Secondary relationship',
    record_status VARCHAR(64) COMMENT 'Queue or lifecycle selector',
    unique_key VARCHAR(255) UNIQUE COMMENT 'Business idempotency key',
    snapshot_json LONGTEXT NOT NULL COMMENT 'Additional immutable snapshot metadata',
    row_version BIGINT NOT NULL DEFAULT 1 COMMENT 'Optimistic revision',
    created_at DATETIME(3) NOT NULL COMMENT 'UTC creation time',
    updated_at DATETIME(3) NOT NULL COMMENT 'UTC update time',
    person_id VARCHAR(128) COMMENT 'personId',
    assignment_id VARCHAR(128) COMMENT 'assignmentId',
    task_material_id VARCHAR(128) COMMENT 'taskMaterialId',
    binding_id VARCHAR(128) COMMENT 'bindingId',
    state VARCHAR(64) COMMENT 'state',
    expires_ms BIGINT COMMENT 'expiresMs',
    started_ms BIGINT COMMENT 'startedMs',
    last_received_ms BIGINT COMMENT 'lastReceivedMs',
    received_seq BIGINT COMMENT 'receivedSeq',
    verified_seq BIGINT COMMENT 'verifiedSeq',
    previous_sample LONGTEXT COMMENT 'previousSample',
    baseline_server_ms BIGINT COMMENT 'baselineServerMs',
    pending_gap_since BIGINT COMMENT 'pendingGapSince',
    speed_window LONGTEXT COMMENT 'speedWindow'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT 'learning_session domain data';
CREATE INDEX idx_learning_session_ref ON learning_session(ref_id, ref2);
CREATE INDEX idx_learning_session_status ON learning_session(record_status, created_at);
CREATE TABLE IF NOT EXISTS learning_event (
    id VARCHAR(64) PRIMARY KEY COMMENT 'Server generated identifier',
    ref_id VARCHAR(128) COMMENT 'Indexed owning aggregate',
    ref2 VARCHAR(128) COMMENT 'Secondary relationship',
    record_status VARCHAR(64) COMMENT 'Queue or lifecycle selector',
    unique_key VARCHAR(255) UNIQUE COMMENT 'Business idempotency key',
    snapshot_json LONGTEXT NOT NULL COMMENT 'Additional immutable snapshot metadata',
    row_version BIGINT NOT NULL DEFAULT 1 COMMENT 'Optimistic revision',
    created_at DATETIME(3) NOT NULL COMMENT 'UTC creation time',
    updated_at DATETIME(3) NOT NULL COMMENT 'UTC update time',
    session_id VARCHAR(128) COMMENT 'sessionId',
    event_id VARCHAR(128) COMMENT 'eventId',
    seq BIGINT COMMENT 'seq',
    batch_id VARCHAR(128) COMMENT 'batchId',
    type VARCHAR(64) COMMENT 'type',
    elapsed_ms BIGINT COMMENT 'elapsedMs',
    payload LONGTEXT COMMENT 'payload',
    payload_hash VARCHAR(64) COMMENT 'payloadHash',
    received_ms BIGINT COMMENT 'receivedMs',
    accepted BOOLEAN COMMENT 'accepted',
    counted BOOLEAN COMMENT 'counted',
    reason VARCHAR(512) COMMENT 'reason',
    decision VARCHAR(64) COMMENT 'decision',
    UNIQUE (session_id, seq)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT 'learning_event domain data';
CREATE INDEX idx_learning_event_ref ON learning_event(ref_id, ref2);
CREATE INDEX idx_learning_event_status ON learning_event(record_status, created_at);
CREATE TABLE IF NOT EXISTS learning_progress (
    id VARCHAR(64) PRIMARY KEY COMMENT 'Server generated identifier',
    ref_id VARCHAR(128) COMMENT 'Indexed owning aggregate',
    ref2 VARCHAR(128) COMMENT 'Secondary relationship',
    record_status VARCHAR(64) COMMENT 'Queue or lifecycle selector',
    unique_key VARCHAR(255) UNIQUE COMMENT 'Business idempotency key',
    snapshot_json LONGTEXT NOT NULL COMMENT 'Additional immutable snapshot metadata',
    row_version BIGINT NOT NULL DEFAULT 1 COMMENT 'Optimistic revision',
    created_at DATETIME(3) NOT NULL COMMENT 'UTC creation time',
    updated_at DATETIME(3) NOT NULL COMMENT 'UTC update time',
    assignment_id VARCHAR(128) COMMENT 'assignmentId',
    task_material_id VARCHAR(128) COMMENT 'taskMaterialId',
    effective_ms BIGINT COMMENT 'effectiveMs',
    covered_ranges LONGTEXT COMMENT 'coveredRanges',
    page_progress LONGTEXT COMMENT 'pageProgress',
    requirements_met BOOLEAN COMMENT 'requirementsMet',
    coverage_ratio DECIMAL(12,9) COMMENT 'coverageRatio',
    last_sample LONGTEXT COMMENT 'lastSample',
    UNIQUE (assignment_id, task_material_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT 'learning_progress domain data';
CREATE INDEX idx_learning_progress_ref ON learning_progress(ref_id, ref2);
CREATE INDEX idx_learning_progress_status ON learning_progress(record_status, created_at);
CREATE TABLE IF NOT EXISTS notification (
    id VARCHAR(64) PRIMARY KEY COMMENT 'Server generated identifier',
    ref_id VARCHAR(128) COMMENT 'Indexed owning aggregate',
    ref2 VARCHAR(128) COMMENT 'Secondary relationship',
    record_status VARCHAR(64) COMMENT 'Queue or lifecycle selector',
    unique_key VARCHAR(255) UNIQUE COMMENT 'Business idempotency key',
    snapshot_json LONGTEXT NOT NULL COMMENT 'Additional immutable snapshot metadata',
    row_version BIGINT NOT NULL DEFAULT 1 COMMENT 'Optimistic revision',
    created_at DATETIME(3) NOT NULL COMMENT 'UTC creation time',
    updated_at DATETIME(3) NOT NULL COMMENT 'UTC update time',
    assignment_id VARCHAR(128) COMMENT 'assignmentId',
    person_id VARCHAR(128) COMMENT 'personId',
    task_id VARCHAR(128) COMMENT 'taskId',
    kind VARCHAR(64) COMMENT 'kind',
    operation_key VARCHAR(255) COMMENT 'operationKey',
    send_status VARCHAR(64) COMMENT 'sendStatus',
    delivery_status VARCHAR(64) COMMENT 'deliveryStatus',
    opened_at DATETIME(3) COMMENT 'openedAt',
    previous_notification_id VARCHAR(128) COMMENT 'previousNotificationId',
    next_attempt_ms BIGINT COMMENT 'nextAttemptMs',
    attempt_count BIGINT COMMENT 'attemptCount',
    simulate_outcome VARCHAR(64) COMMENT 'simulateOutcome',
    error VARCHAR(512) COMMENT 'error',
    provider_mode VARCHAR(64) COMMENT 'providerMode',
    started_ms BIGINT COMMENT 'startedMs'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT 'notification domain data';
CREATE INDEX idx_notification_ref ON notification(ref_id, ref2);
CREATE INDEX idx_notification_status ON notification(record_status, created_at);
CREATE TABLE IF NOT EXISTS notification_attempt (
    id VARCHAR(64) PRIMARY KEY COMMENT 'Server generated identifier',
    ref_id VARCHAR(128) COMMENT 'Indexed owning aggregate',
    ref2 VARCHAR(128) COMMENT 'Secondary relationship',
    record_status VARCHAR(64) COMMENT 'Queue or lifecycle selector',
    unique_key VARCHAR(255) UNIQUE COMMENT 'Business idempotency key',
    snapshot_json LONGTEXT NOT NULL COMMENT 'Additional immutable snapshot metadata',
    row_version BIGINT NOT NULL DEFAULT 1 COMMENT 'Optimistic revision',
    created_at DATETIME(3) NOT NULL COMMENT 'UTC creation time',
    updated_at DATETIME(3) NOT NULL COMMENT 'UTC update time',
    notification_id VARCHAR(128) COMMENT 'notificationId',
    binding_id VARCHAR(128) COMMENT 'bindingId',
    subscription_record_id VARCHAR(128) COMMENT 'subscriptionRecordId',
    template_snapshot LONGTEXT COMMENT 'templateSnapshot',
    payload_snapshot LONGTEXT COMMENT 'payloadSnapshot',
    send_status VARCHAR(64) COMMENT 'sendStatus',
    delivery_status VARCHAR(64) COMMENT 'deliveryStatus',
    send_errcode BIGINT COMMENT 'sendErrcode',
    delivery_errcode BIGINT COMMENT 'deliveryErrcode',
    provider_msg_id VARCHAR(128) COMMENT 'providerMsgId',
    started_ms BIGINT COMMENT 'startedMs',
    responded_at DATETIME(3) COMMENT 'respondedAt',
    callback_id VARCHAR(128) COMMENT 'callbackId'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT 'notification_attempt domain data';
CREATE INDEX idx_notification_attempt_ref ON notification_attempt(ref_id, ref2);
CREATE INDEX idx_notification_attempt_status ON notification_attempt(record_status, created_at);
CREATE TABLE IF NOT EXISTS subscription_record (
    id VARCHAR(64) PRIMARY KEY COMMENT 'Server generated identifier',
    ref_id VARCHAR(128) COMMENT 'Indexed owning aggregate',
    ref2 VARCHAR(128) COMMENT 'Secondary relationship',
    record_status VARCHAR(64) COMMENT 'Queue or lifecycle selector',
    unique_key VARCHAR(255) UNIQUE COMMENT 'Business idempotency key',
    snapshot_json LONGTEXT NOT NULL COMMENT 'Additional immutable snapshot metadata',
    row_version BIGINT NOT NULL DEFAULT 1 COMMENT 'Optimistic revision',
    created_at DATETIME(3) NOT NULL COMMENT 'UTC creation time',
    updated_at DATETIME(3) NOT NULL COMMENT 'UTC update time',
    binding_id VARCHAR(128) COMMENT 'bindingId',
    identity_id VARCHAR(128) COMMENT 'identityId',
    template_id VARCHAR(128) COMMENT 'templateId',
    nonce_hash VARCHAR(64) COMMENT 'nonceHash',
    expires_ms BIGINT COMMENT 'expiresMs',
    client_result VARCHAR(64) COMMENT 'clientResult',
    confidence VARCHAR(64) COMMENT 'confidence',
    result VARCHAR(64) COMMENT 'result',
    hint_used_by_notification_id VARCHAR(128) COMMENT 'hintUsedByNotificationId',
    invalidated BOOLEAN COMMENT 'invalidated',
    provider_callback_id VARCHAR(128) COMMENT 'providerCallbackId'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT 'subscription_record domain data';
CREATE INDEX idx_subscription_record_ref ON subscription_record(ref_id, ref2);
CREATE INDEX idx_subscription_record_status ON subscription_record(record_status, created_at);
CREATE TABLE IF NOT EXISTS wechat_callback (
    id VARCHAR(64) PRIMARY KEY COMMENT 'Server generated identifier',
    ref_id VARCHAR(128) COMMENT 'Indexed owning aggregate',
    ref2 VARCHAR(128) COMMENT 'Secondary relationship',
    record_status VARCHAR(64) COMMENT 'Queue or lifecycle selector',
    unique_key VARCHAR(255) UNIQUE COMMENT 'Business idempotency key',
    snapshot_json LONGTEXT NOT NULL COMMENT 'Additional immutable snapshot metadata',
    row_version BIGINT NOT NULL DEFAULT 1 COMMENT 'Optimistic revision',
    created_at DATETIME(3) NOT NULL COMMENT 'UTC creation time',
    updated_at DATETIME(3) NOT NULL COMMENT 'UTC update time',
    event_type VARCHAR(64) COMMENT 'eventType',
    provider_msg_id VARCHAR(128) COMMENT 'providerMsgId',
    matched_attempt_id VARCHAR(128) COMMENT 'matchedAttemptId',
    match_status VARCHAR(64) COMMENT 'matchStatus',
    receive_count BIGINT COMMENT 'receiveCount',
    processing_state VARCHAR(64) COMMENT 'processingState',
    payload LONGTEXT COMMENT 'payload'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT 'wechat_callback domain data';
CREATE INDEX idx_wechat_callback_ref ON wechat_callback(ref_id, ref2);
CREATE INDEX idx_wechat_callback_status ON wechat_callback(record_status, created_at);
CREATE TABLE IF NOT EXISTS audit_log (
    id VARCHAR(64) PRIMARY KEY COMMENT 'Server generated identifier',
    ref_id VARCHAR(128) COMMENT 'Indexed owning aggregate',
    ref2 VARCHAR(128) COMMENT 'Secondary relationship',
    record_status VARCHAR(64) COMMENT 'Queue or lifecycle selector',
    unique_key VARCHAR(255) UNIQUE COMMENT 'Business idempotency key',
    snapshot_json LONGTEXT NOT NULL COMMENT 'Additional immutable snapshot metadata',
    row_version BIGINT NOT NULL DEFAULT 1 COMMENT 'Optimistic revision',
    created_at DATETIME(3) NOT NULL COMMENT 'UTC creation time',
    updated_at DATETIME(3) NOT NULL COMMENT 'UTC update time',
    actor_id VARCHAR(128) COMMENT 'actorId',
    action VARCHAR(512) COMMENT 'action',
    target_id VARCHAR(128) COMMENT 'targetId',
    occurred_at DATETIME(3) COMMENT 'occurredAt',
    detail LONGTEXT COMMENT 'detail'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT 'audit_log domain data';
CREATE INDEX idx_audit_log_ref ON audit_log(ref_id, ref2);
CREATE INDEX idx_audit_log_status ON audit_log(record_status, created_at);
CREATE TABLE IF NOT EXISTS app_config (
    id VARCHAR(64) PRIMARY KEY COMMENT 'Server generated identifier',
    ref_id VARCHAR(128) COMMENT 'Indexed owning aggregate',
    ref2 VARCHAR(128) COMMENT 'Secondary relationship',
    record_status VARCHAR(64) COMMENT 'Queue or lifecycle selector',
    unique_key VARCHAR(255) UNIQUE COMMENT 'Business idempotency key',
    snapshot_json LONGTEXT NOT NULL COMMENT 'Additional immutable snapshot metadata',
    row_version BIGINT NOT NULL DEFAULT 1 COMMENT 'Optimistic revision',
    created_at DATETIME(3) NOT NULL COMMENT 'UTC creation time',
    updated_at DATETIME(3) NOT NULL COMMENT 'UTC update time',
    template_id VARCHAR(128) COMMENT 'templateId',
    field_mapping LONGTEXT COMMENT 'fieldMapping'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT 'app_config domain data';
CREATE INDEX idx_app_config_ref ON app_config(ref_id, ref2);
CREATE INDEX idx_app_config_status ON app_config(record_status, created_at);
CREATE TABLE IF NOT EXISTS auth_token (
    id VARCHAR(64) PRIMARY KEY COMMENT 'Server generated identifier',
    ref_id VARCHAR(128) COMMENT 'Indexed owning aggregate',
    ref2 VARCHAR(128) COMMENT 'Secondary relationship',
    record_status VARCHAR(64) COMMENT 'Queue or lifecycle selector',
    unique_key VARCHAR(255) UNIQUE COMMENT 'Business idempotency key',
    snapshot_json LONGTEXT NOT NULL COMMENT 'Additional immutable snapshot metadata',
    row_version BIGINT NOT NULL DEFAULT 1 COMMENT 'Optimistic revision',
    created_at DATETIME(3) NOT NULL COMMENT 'UTC creation time',
    updated_at DATETIME(3) NOT NULL COMMENT 'UTC update time',
    role VARCHAR(64) COMMENT 'role',
    actor_id VARCHAR(128) COMMENT 'actorId',
    encrypted_openid LONGTEXT COMMENT 'encryptedOpenid',
    expires_ms BIGINT COMMENT 'expiresMs',
    status VARCHAR(64) COMMENT 'status'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT 'auth_token domain data';
CREATE INDEX idx_auth_token_ref ON auth_token(ref_id, ref2);
CREATE INDEX idx_auth_token_status ON auth_token(record_status, created_at);
CREATE TABLE IF NOT EXISTS media_ticket (
    id VARCHAR(64) PRIMARY KEY COMMENT 'Server generated identifier',
    ref_id VARCHAR(128) COMMENT 'Indexed owning aggregate',
    ref2 VARCHAR(128) COMMENT 'Secondary relationship',
    record_status VARCHAR(64) COMMENT 'Queue or lifecycle selector',
    unique_key VARCHAR(255) UNIQUE COMMENT 'Business idempotency key',
    snapshot_json LONGTEXT NOT NULL COMMENT 'Additional immutable snapshot metadata',
    row_version BIGINT NOT NULL DEFAULT 1 COMMENT 'Optimistic revision',
    created_at DATETIME(3) NOT NULL COMMENT 'UTC creation time',
    updated_at DATETIME(3) NOT NULL COMMENT 'UTC update time',
    material_id VARCHAR(128) COMMENT 'materialId',
    page_no BIGINT COMMENT 'pageNo',
    expires_ms BIGINT COMMENT 'expiresMs',
    ticket_hash VARCHAR(64) COMMENT 'ticketHash',
    path LONGTEXT COMMENT 'path',
    purpose VARCHAR(64) COMMENT 'purpose',
    file_key VARCHAR(255) COMMENT 'fileKey',
    content_type VARCHAR(512) COMMENT 'contentType'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT 'media_ticket domain data';
CREATE INDEX idx_media_ticket_ref ON media_ticket(ref_id, ref2);
CREATE INDEX idx_media_ticket_status ON media_ticket(record_status, created_at);
CREATE TABLE IF NOT EXISTS mock_card (
    id VARCHAR(64) PRIMARY KEY COMMENT 'Server generated identifier',
    ref_id VARCHAR(128) COMMENT 'Indexed owning aggregate',
    ref2 VARCHAR(128) COMMENT 'Secondary relationship',
    record_status VARCHAR(64) COMMENT 'Queue or lifecycle selector',
    unique_key VARCHAR(255) UNIQUE COMMENT 'Business idempotency key',
    snapshot_json LONGTEXT NOT NULL COMMENT 'Additional immutable snapshot metadata',
    row_version BIGINT NOT NULL DEFAULT 1 COMMENT 'Optimistic revision',
    created_at DATETIME(3) NOT NULL COMMENT 'UTC creation time',
    updated_at DATETIME(3) NOT NULL COMMENT 'UTC update time',
    notification_id VARCHAR(128) COMMENT 'notificationId',
    assignment_id VARCHAR(128) COMMENT 'assignmentId',
    person_id VARCHAR(128) COMMENT 'personId',
    title VARCHAR(512) COMMENT 'title',
    provider_msg_id VARCHAR(128) COMMENT 'providerMsgId',
    deep_link LONGTEXT COMMENT 'deepLink',
    miniprogram_page LONGTEXT COMMENT 'miniprogramPage',
    provider_mode VARCHAR(64) COMMENT 'providerMode'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT 'mock_card domain data';
CREATE INDEX idx_mock_card_ref ON mock_card(ref_id, ref2);
CREATE INDEX idx_mock_card_status ON mock_card(record_status, created_at);

CREATE INDEX idx_learning_event_ref_seq ON learning_event(ref_id, seq);

CREATE TABLE IF NOT EXISTS background_work (
    id VARCHAR(64) PRIMARY KEY COMMENT 'Hash of work kind and target',
    kind VARCHAR(32) NOT NULL COMMENT 'MEDIA or NOTIFICATIONS',
    target_id VARCHAR(128) NOT NULL COMMENT 'Material or person identifier or all',
    status VARCHAR(32) NOT NULL COMMENT 'Durable execution state',
    generation BIGINT NOT NULL COMMENT 'Latest committed wake generation',
    running_generation BIGINT NOT NULL COMMENT 'Generation claimed by current worker',
    cursor_id VARCHAR(64) NOT NULL COMMENT 'Last processed notification id',
    lease_until_ms BIGINT NOT NULL COMMENT 'UTC lease expiration milliseconds',
    lease_token VARCHAR(128) NOT NULL COMMENT 'Fences stale workers',
    attempts INT NOT NULL COMMENT 'Consecutive failed executions',
    error_code VARCHAR(128) NOT NULL COMMENT 'Sanitized recovery code',
    updated_ms BIGINT NOT NULL COMMENT 'UTC update milliseconds'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT 'Durable event driven work with leases';
