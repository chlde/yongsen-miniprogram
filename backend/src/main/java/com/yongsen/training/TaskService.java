package com.yongsen.training;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class TaskService {
    public static final String CONFIRMATION_VERSION = "mvp-1";
    public static final String CONFIRMATION_TEXT = "我确认由本人完成本任务所列材料的学习，并已阅读任务要求。";
    private static final int MAX_PEOPLE = 500;
    private static final int MAX_MATERIALS = 10;
    private final Store store;
    private final AuthService auth;
    private final NotificationService notifications;

    public TaskService(Store store, AuthService auth, NotificationService notifications) {
        this.store = store;
        this.auth = auth;
        this.notifications = notifications;
    }

    public Map<String, Object> create(Map<String, Object> input, String actor) {
        validateDraft(input);
        Map<String, Object> task = new LinkedHashMap<>(input);
        task.putAll(Data.map("status", "DRAFT", "createdBy", actor));
        return store.create(Store.Table.TASK, task, actor, null, "DRAFT", null);
    }

    private void validateDraft(Map<String, Object> input) {
        Data.require(
                Data.required(input, "title").length() <= 200,
                "INVALID_INPUT",
                "Title exceeds 200 characters");
        Data.require(
                Data.str(input, "description").length() <= 10000,
                "INVALID_INPUT",
                "Description exceeds 10000 characters");
        Data.require(
                Data.list(input.get("personIds")).size() > 0
                        && Data.list(input.get("personIds")).size() <= MAX_PEOPLE,
                "INVALID_INPUT",
                "Select 1 to 500 people");
        Data.require(
                !Data.list(input.get("materials")).isEmpty()
                        && Data.list(input.get("materials")).size() <= MAX_MATERIALS,
                "INVALID_INPUT",
                "Select 1 to 10 materials");
        if (!Data.str(input, "dueAt").isBlank()) {
            try {
                Instant.parse(Data.str(input, "dueAt"));
            } catch (java.time.format.DateTimeParseException exception) {
                throw new BusinessException("INVALID_INPUT", "dueAt must be an ISO timestamp", 400);
            }
        }
    }

    @Transactional
    public Map<String, Object> edit(String id, Map<String, Object> input) {
        Map<String, Object> task = store.lock(Store.Table.TASK, id).toBoundary();
        Data.require(
                "DRAFT".equals(task.get("status")),
                "TASK_IMMUTABLE",
                "Published tasks cannot change");
        if (input.containsKey("expectedRevision")
                && Data.num(input, "expectedRevision", 0) != Data.num(task, "rowVersion", 0)) {
            throw new BusinessException("VERSION_CONFLICT", "Refresh before updating", 409);
        }
        for (String field : List.of("title", "description", "dueAt", "personIds", "materials")) {
            if (input.containsKey(field)) {
                task.put(field, input.get(field));
            }
        }
        validateDraft(task);
        store.save(Store.Table.TASK, task, "DRAFT");
        return Store.publicView(task);
    }

    @Transactional
    public Map<String, Object> publish(String id, String key, String actor) {
        Data.require(!key.isBlank(), "IDEMPOTENCY_REQUIRED", "Idempotency-Key required");
        Map<String, Object> task = store.lock(Store.Table.TASK, id).toBoundary();
        if ("ACTIVE".equals(task.get("status"))) {
            Data.require(
                    key.equals(task.get("publishKey")),
                    "ALREADY_PUBLISHED",
                    "Task already published");
            return published(task);
        }
        Data.require(
                "DRAFT".equals(task.get("status")), "TASK_NOT_ACTIVE", "Task cannot be published");
        validateDraft(task);
        notifications.validateTaskForNotification(task);
        List<String> people =
                Data.list(task.get("personIds")).stream().map(Object::toString).distinct().toList();
        List<Map<String, Object>> items = freezeMaterials(task);
        for (String personId : people) {
            Map<String, Object> person = store.get(Store.Table.PERSON, personId).toBoundary();
            Data.require(
                    "ACTIVE".equals(person.get("status")),
                    "PERSON_INACTIVE",
                    "A selected person is inactive");
            Map<String, Object> assignment =
                    store.create(
                            Store.Table.ASSIGNMENT,
                            Data.map(
                                    "taskId",
                                    id,
                                    "personId",
                                    personId,
                                    "personName",
                                    person.get("name"),
                                    "personSnapshot",
                                    Store.publicView(person),
                                    "status",
                                    "NOT_STARTED"),
                            id,
                            personId,
                            "NOT_STARTED",
                            id + ":" + personId);
            for (Map<String, Object> item : items) {
                String progressKey = assignment.get("id") + ":" + item.get("id");
                store.create(
                        Store.Table.LEARNING_PROGRESS,
                        Data.map(
                                "assignmentId",
                                assignment.get("id"),
                                "taskMaterialId",
                                item.get("id"),
                                "effectiveMs",
                                0L,
                                "coveredRanges",
                                List.of(),
                                "pageProgress",
                                new LinkedHashMap<>(),
                                "requirementsMet",
                                false,
                                "coverageRatio",
                                0.0),
                        Data.str(assignment, "id"),
                        Data.str(item, "id"),
                        "NOT_STARTED",
                        progressKey);
            }
            notifications.create(assignment, "ASSIGNMENT", key, null);
        }
        task.putAll(Data.map("status", "ACTIVE", "publishedAt", Data.now(), "publishKey", key));
        store.save(Store.Table.TASK, task, "ACTIVE");
        store.audit(actor, "TASK_PUBLISHED", id, Data.map("recipientCount", people.size()));
        return published(task);
    }

    private List<Map<String, Object>> freezeMaterials(Map<String, Object> task) {
        List<Map<String, Object>> items = new ArrayList<>();
        for (Object raw : Data.list(task.get("materials"))) {
            Map<String, Object> selection = Data.obj(raw);
            String materialId = Data.required(selection, "materialId");
            Map<String, Object> material = store.get(Store.Table.MATERIAL, materialId).toBoundary();
            Data.require(
                    "READY".equals(material.get("status"))
                            && Data.bool(material, "compatibilityChecked"),
                    "MATERIAL_NOT_READY",
                    "Material must be processed and compatibility checked");
            Map<String, Object> rule = new LinkedHashMap<>(Data.obj(selection.get("rule")));
            if ("MP4".equals(material.get("type"))) {
                Data.require(
                        Data.decimal(rule, "playbackRate", 1.0) == 1.0,
                        "INVALID_RULE",
                        "Video requires 1x playback");
                rule.put("playbackRate", 1.0);
            } else {
                long minimum = Data.num(rule, "minPageVisibleMs", 30000);
                Data.require(
                        minimum >= 1000 && minimum <= 3600000,
                        "INVALID_RULE",
                        "Invalid page minimum");
                rule.put("minPageVisibleMs", minimum);
            }
            Map<String, Object> item =
                    Data.map(
                            "taskId",
                            task.get("id"),
                            "sortOrder",
                            items.size(),
                            "materialId",
                            materialId,
                            "title",
                            material.get("title"),
                            "type",
                            material.get("type"),
                            "durationMs",
                            material.get("durationMs"),
                            "pageCount",
                            material.get("pageCount"),
                            "pages",
                            material.getOrDefault("pages", List.of()),
                            "rule",
                            rule,
                            "materialSnapshot",
                            Store.publicView(material),
                            "ruleHash",
                            Data.hash(Data.json(rule)));
            items.add(
                    store.create(
                            Store.Table.TASK_MATERIAL,
                            item,
                            Data.str(task, "id"),
                            materialId,
                            "ACTIVE",
                            task.get("id") + ":" + materialId));
        }
        return items;
    }

    private Map<String, Object> published(Map<String, Object> task) {
        int count =
                store.refs(Store.Table.ASSIGNMENT, Data.str(task, "id")).stream()
                        .map(StoredRecordDO::toBoundary)
                        .toList()
                        .size();
        return Data.map(
                "id",
                task.get("id"),
                "taskId",
                task.get("id"),
                "status",
                task.get("status"),
                "assignmentCount",
                count,
                "notificationCount",
                count);
    }

    public Map<String, Object> authorizeAssignment(String id, Map<String, Object> actor) {
        Map<String, Object> assignment = store.get(Store.Table.ASSIGNMENT, id).toBoundary();
        if (!"ADMIN".equals(actor.get("role"))) {
            Map<String, Object> binding = auth.binding(actor, true);
            if (!assignment.get("personId").equals(binding.get("personId"))) {
                throw new BusinessException("FORBIDDEN", "Not your assignment", 403);
            }
        }
        return assignment;
    }

    public List<Map<String, Object>> mine(Map<String, Object> actor) {
        String personId = Data.str(auth.binding(actor, true), "personId");
        return store.all(Store.Table.ASSIGNMENT).stream()
                .map(StoredRecordDO::toBoundary)
                .toList()
                .stream()
                .filter(row -> personId.equals(row.get("personId")))
                .map(row -> summary(row))
                .toList();
    }

    public Map<String, Object> summary(Map<String, Object> assignment) {
        Map<String, Object> result = Store.publicView(assignment);
        Map<String, Object> task =
                store.get(Store.Table.TASK, Data.str(assignment, "taskId")).toBoundary();
        result.putAll(
                Data.map(
                        "title",
                        task.get("title"),
                        "dueAt",
                        task.get("dueAt"),
                        "taskStatus",
                        task.get("status")));
        return result;
    }

    public Map<String, Object> detail(String id, Map<String, Object> actor) {
        Map<String, Object> assignment = authorizeAssignment(id, actor);
        Map<String, Object> result = summary(assignment);
        List<Map<String, Object>> items = new ArrayList<>();
        boolean satisfied = true;
        for (Map<String, Object> item : materials(Data.str(assignment, "taskId"))) {
            Map<String, Object> view = Store.publicView(item);
            view.put("taskMaterialId", item.get("id"));
            Map<String, Object> progress =
                    store.byKey(Store.Table.LEARNING_PROGRESS, id + ":" + item.get("id"))
                            .toBoundary();
            view.put("progress", progressView(progress, item));
            satisfied = satisfied && Data.bool(progress, "requirementsMet");
            items.add(view);
        }
        result.putAll(
                Data.map(
                        "materials",
                        items,
                        "requirementsMet",
                        satisfied,
                        "confirmationText",
                        CONFIRMATION_TEXT,
                        "confirmationTextVersion",
                        CONFIRMATION_VERSION,
                        "progressDigest",
                        digest(assignment, items)));
        return result;
    }

    public List<Map<String, Object>> materials(String taskId) {
        return store.refs(Store.Table.TASK_MATERIAL, taskId).stream()
                .map(StoredRecordDO::toBoundary)
                .sorted(
                        java.util.Comparator.<Map<String, Object>>comparingLong(
                                        row -> Data.num(row, "sortOrder", Long.MAX_VALUE))
                                .thenComparingLong(row -> Data.num(row, "_createdMs", 0))
                                .thenComparing(row -> Data.str(row, "id")))
                .toList();
    }

    public static String digest(Map<String, Object> assignment, List<Map<String, Object>> items) {
        return Data.hash(Data.str(assignment, "id") + Data.json(items));
    }

    public Map<String, Object> progressView(
            Map<String, Object> progress, Map<String, Object> item) {
        Map<String, Object> view = Store.publicView(progress);
        if ("MP4".equals(item.get("type"))) {
            view.put(
                    "missingRangesMs",
                    LearningMath.missing(
                            Data.list(progress.get("coveredRanges")),
                            Data.num(item, "durationMs", 0)));
        }
        return view;
    }

    @Transactional
    public Map<String, Object> confirm(
            String id, Map<String, Object> actor, Map<String, Object> input, String key) {
        auth.binding(actor, true);
        authorizeAssignment(id, actor);
        Map<String, Object> assignment = store.lock(Store.Table.ASSIGNMENT, id).toBoundary();
        Data.require(
                !key.isBlank() && key.length() <= 128,
                "IDEMPOTENCY_REQUIRED",
                "Idempotency-Key required, maximum 128 characters");
        String requestHash = Data.contentHash(input);
        if ("COMPLETED".equals(assignment.get("status"))) {
            if (key.equals(assignment.get("confirmKey"))
                    && !requestHash.equals(assignment.get("confirmRequestHash"))) {
                throw new BusinessException(
                        "IDEMPOTENCY_CONFLICT", "Key was used for different input", 409);
            }
            return Data.map(
                    "status",
                    "COMPLETED",
                    "confirmedAt",
                    assignment.get("confirmedAt"),
                    "completedAt",
                    assignment.get("completedAt"));
        }
        Data.require(!key.isBlank(), "IDEMPOTENCY_REQUIRED", "Idempotency-Key required");
        Map<String, Object> task =
                store.get(Store.Table.TASK, Data.str(assignment, "taskId")).toBoundary();
        Data.require("ACTIVE".equals(task.get("status")), "TASK_NOT_ACTIVE", "Task is not active");
        Map<String, Object> detail = detail(id, actor);
        Data.require(
                Data.bool(detail, "requirementsMet"),
                "LEARNING_NOT_COMPLETE",
                "Complete all learning first");
        Data.require(
                Data.str(input, "progressDigest").equals(detail.get("progressDigest")),
                "PROGRESS_CHANGED",
                "Refresh the completion summary");
        Data.require(
                Data.bool(input, "accepted")
                        && CONFIRMATION_VERSION.equals(input.get("confirmationTextVersion")),
                "CONFIRMATION_REQUIRED",
                "Personal confirmation is required");
        String now = Data.now();
        Map<String, Object> bindingSnapshot = auth.publicBinding(auth.binding(actor, true));
        Map<String, Object> snapshot = new LinkedHashMap<>(detail);
        snapshot.put("bindingSnapshot", bindingSnapshot);
        assignment.putAll(
                Data.map(
                        "status",
                        "COMPLETED",
                        "confirmedAt",
                        now,
                        "completedAt",
                        now,
                        "confirmationSnapshot",
                        snapshot,
                        "confirmationHash",
                        Data.hash(Data.json(snapshot)),
                        "confirmKey",
                        key,
                        "confirmRequestHash",
                        requestHash,
                        "bindingSnapshot",
                        bindingSnapshot));
        store.save(Store.Table.ASSIGNMENT, assignment, "COMPLETED");
        notifications.cancelPending(id);
        store.audit(
                Data.str(actor, "actorId"),
                "PERSONALLY_CONFIRMED",
                id,
                Data.map("snapshotHash", assignment.get("confirmationHash")));
        return Data.map("status", "COMPLETED", "confirmedAt", now, "completedAt", now);
    }

    @Transactional
    public void cancel(String id, String reason, String actor) {
        Map<String, Object> task = store.lock(Store.Table.TASK, id).toBoundary();
        task.putAll(Data.map("status", "CANCELLED", "cancellationReason", reason));
        store.save(Store.Table.TASK, task, "CANCELLED");
        for (Map<String, Object> assignment :
                store.refs(Store.Table.ASSIGNMENT, id).stream()
                        .map(StoredRecordDO::toBoundary)
                        .toList()) {
            notifications.cancelPending(Data.str(assignment, "id"));
            for (Map<String, Object> session :
                    store.all(Store.Table.LEARNING_SESSION).stream()
                            .map(StoredRecordDO::toBoundary)
                            .toList()) {
                if (assignment.get("id").equals(session.get("assignmentId"))
                        && "ACTIVE".equals(session.get("state"))) {
                    session.put("state", "REVOKED");
                    store.save(Store.Table.LEARNING_SESSION, session, "REVOKED");
                }
            }
        }
        store.audit(actor, "TASK_CANCELLED", id, Data.map("reason", reason));
    }
}
