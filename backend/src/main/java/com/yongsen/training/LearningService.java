package com.yongsen.training;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class LearningService {
    private static final long LEASE_MS = 30000;
    private static final long MAX_RECEIVE_GAP_MS = 15000;
    private static final int MAX_BATCH_EVENTS = 50;
    private static final int MAX_BATCH_BYTES = 65536;
    private final Store store;
    private final AuthService auth;
    private final TaskService tasks;
    private final java.time.Clock clock;

    public LearningService(
            Store store, AuthService auth, TaskService tasks, java.time.Clock clock) {
        this.store = store;
        this.auth = auth;
        this.tasks = tasks;
        this.clock = clock;
    }

    @Transactional
    public Map<String, Object> start(
            Map<String, Object> actor, Map<String, Object> input, String takeoverId) {
        Map<String, Object> binding = auth.binding(actor, true);
        Map<String, Object> person =
                store.lock(Store.Table.PERSON, Data.str(binding, "personId")).toBoundary();
        String assignmentId = Data.required(input, "assignmentId");
        Map<String, Object> assignment = tasks.authorizeAssignment(assignmentId, actor);
        Map<String, Object> task =
                store.get(Store.Table.TASK, Data.str(assignment, "taskId")).toBoundary();
        Data.require("ACTIVE".equals(task.get("status")), "TASK_NOT_ACTIVE", "Task is not active");
        Data.require(
                !"COMPLETED".equals(assignment.get("status")),
                "ALREADY_COMPLETED",
                "Task already complete");
        String itemId = Data.required(input, "taskMaterialId");
        Map<String, Object> item = store.get(Store.Table.TASK_MATERIAL, itemId).toBoundary();
        Data.require(
                item.get("taskId").equals(task.get("id")),
                "INVALID_MATERIAL",
                "Material does not belong to task");
        String activeId = Data.str(person, "activeSessionId");
        if (!activeId.isBlank()) {
            Map<String, Object> active =
                    store.get(Store.Table.LEARNING_SESSION, activeId).toBoundary();
            boolean live =
                    "ACTIVE".equals(active.get("state"))
                            && Data.num(active, "expiresMs", 0) > clock.millis();
            if (live && !activeId.equals(takeoverId)) {
                throw new BusinessException(
                        "SESSION_IN_USE",
                        "Another learning session is active",
                        409,
                        Data.map("sessionId", activeId));
            }
            if (live || "ACTIVE".equals(active.get("state"))) {
                active.put("state", live ? "REPLACED" : "EXPIRED");
                store.save(Store.Table.LEARNING_SESSION, active, Data.str(active, "state"));
            }
        }
        long now = clock.millis();
        Map<String, Object> session =
                store.create(
                        Store.Table.LEARNING_SESSION,
                        Data.map(
                                "personId",
                                person.get("id"),
                                "assignmentId",
                                assignmentId,
                                "taskMaterialId",
                                itemId,
                                "bindingId",
                                binding.get("id"),
                                "state",
                                "ACTIVE",
                                "expiresMs",
                                now + LEASE_MS,
                                "startedMs",
                                now,
                                "lastReceivedMs",
                                now,
                                "receivedSeq",
                                0L,
                                "verifiedSeq",
                                0L,
                                "previousSample",
                                Map.of()),
                        Data.str(person, "id"),
                        assignmentId,
                        "ACTIVE",
                        null);
        person.put("activeSessionId", session.get("id"));
        store.save(Store.Table.PERSON, person, "ACTIVE");
        assignment.put("status", "IN_PROGRESS");
        assignment.putIfAbsent("firstLearningAt", Data.now());
        store.save(Store.Table.ASSIGNMENT, assignment, "IN_PROGRESS");
        return response(session, item, List.of());
    }

    @Transactional
    public Map<String, Object> events(
            String sessionId, Map<String, Object> actor, Map<String, Object> input) {
        Map<String, Object> binding = auth.binding(actor, true);
        Map<String, Object> person =
                store.lock(Store.Table.PERSON, Data.str(binding, "personId")).toBoundary();
        Map<String, Object> session =
                store.lock(Store.Table.LEARNING_SESSION, sessionId).toBoundary();
        Data.require(
                person.get("id").equals(session.get("personId")), "FORBIDDEN", "Not your session");
        long now = clock.millis();
        Map<String, Object> item =
                store.get(Store.Table.TASK_MATERIAL, Data.str(session, "taskMaterialId"))
                        .toBoundary();
        List<Object> incoming = Data.list(input.get("events"));
        Data.require(
                incoming.size() <= MAX_BATCH_EVENTS
                        && Data.json(input).getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                                <= MAX_BATCH_BYTES,
                "BATCH_TOO_LARGE",
                "Event batch exceeds limits");
        if (!valid(session, person, binding, now)) {
            if ("ACTIVE".equals(session.get("state"))) {
                session.put("state", "EXPIRED");
                store.save(Store.Table.LEARNING_SESSION, session, "EXPIRED");
            }
            store.audit(
                    Data.str(actor, "actorId"),
                    "LEARNING_BATCH_REJECTED",
                    sessionId,
                    Data.map(
                            "sessionId", sessionId,
                            "batchId", Data.str(input, "batchId"),
                            "eventCount", incoming.size(),
                            "batchHash", Data.contentHash(input),
                            "reason", "SESSION_" + Data.str(session, "state")));
            return response(session, item, List.of());
        }
        Map<String, Object> assignment =
                store.lock(Store.Table.ASSIGNMENT, Data.str(session, "assignmentId")).toBoundary();
        Data.require(
                "ACTIVE"
                        .equals(
                                store.get(Store.Table.TASK, Data.str(assignment, "taskId"))
                                        .toBoundary()
                                        .get("status")),
                "TASK_NOT_ACTIVE",
                "Task is not active");
        List<Map<String, Object>> results =
                ingest(session, incoming, Data.str(input, "batchId"), now);
        process(session, item, now, results);
        session.putAll(Data.map("lastReceivedMs", now, "expiresMs", now + LEASE_MS));
        store.save(Store.Table.LEARNING_SESSION, session, "ACTIVE");
        updateAssignment(assignment);
        return response(session, item, results);
    }

    private boolean valid(
            Map<String, Object> session,
            Map<String, Object> person,
            Map<String, Object> binding,
            long now) {
        return "ACTIVE".equals(session.get("state"))
                && session.get("id").equals(person.get("activeSessionId"))
                && binding.get("id").equals(session.get("bindingId"))
                && Data.num(session, "expiresMs", 0) > now;
    }

    private List<Map<String, Object>> ingest(
            Map<String, Object> session, List<Object> incoming, String batch, long now) {
        List<Map<String, Object>> results = new ArrayList<>();
        for (Object raw : incoming) {
            Map<String, Object> event = Data.obj(raw);
            long seq = Data.num(event, "seq", -1);
            Data.require(
                    seq > 0
                            && seq
                                    <= Math.max(
                                                    Data.num(session, "receivedSeq", 0),
                                                    Data.num(session, "verifiedSeq", 0))
                                            + 200,
                    "INVALID_SEQ",
                    "Sequence outside receive window");
            Data.require(
                    Data.num(event, "elapsedMs", -1) >= 0, "INVALID_EVENT", "elapsedMs required");
            String key = session.get("id") + ":" + seq;
            String digest = Data.contentHash(event);
            Map<String, Object> old =
                    store.findKey(Store.Table.LEARNING_EVENT, key)
                            .map(StoredRecordDO::toBoundary)
                            .orElse(null);
            if (old != null) {
                if (!digest.equals(old.get("payloadHash"))) {
                    store.audit(
                            Data.str(session, "personId"),
                            "EVENT_SEQ_CONFLICT",
                            Data.str(session, "id"),
                            Data.map(
                                    "seq",
                                    seq,
                                    "originalHash",
                                    old.get("payloadHash"),
                                    "attemptedHash",
                                    digest));
                }
                results.add(
                        Data.map(
                                "seq",
                                seq,
                                "accepted",
                                digest.equals(old.get("payloadHash")),
                                "counted",
                                false,
                                "reason",
                                digest.equals(old.get("payloadHash"))
                                        ? "DUPLICATE"
                                        : "EVENT_SEQ_CONFLICT"));
                continue;
            }
            Data.require(
                    List.of(
                                    "PLAY",
                                    "PAUSE",
                                    "WAITING",
                                    "ENDED",
                                    "SEEK",
                                    "VIDEO_SAMPLE",
                                    "SHOW",
                                    "HIDE",
                                    "PAGE_ENTER",
                                    "PAGE_LEAVE",
                                    "DOC_SAMPLE",
                                    "CLOSE")
                            .contains(Data.str(event, "type")),
                    "INVALID_EVENT",
                    "Unsupported event type");
            session.put("maxReceivedSeq", Math.max(seq, Data.num(session, "maxReceivedSeq", 0)));
            Map<String, Object> row = new java.util.LinkedHashMap<>(event);
            row.putAll(
                    Data.map(
                            "sessionId",
                            session.get("id"),
                            "batchId",
                            batch,
                            "payloadHash",
                            digest,
                            "receivedMs",
                            now,
                            "accepted",
                            true,
                            "counted",
                            false,
                            "decision",
                            "PENDING"));
            store.create(
                    Store.Table.LEARNING_EVENT,
                    row,
                    Data.str(session, "id"),
                    Long.toString(seq),
                    "PENDING",
                    key);
            results.add(
                    Data.map("seq", seq, "accepted", true, "counted", false, "reason", "RECEIVED"));
        }
        return results;
    }

    private void process(
            Map<String, Object> session,
            Map<String, Object> item,
            long now,
            List<Map<String, Object>> results) {
        long verified = Data.num(session, "verifiedSeq", 0);
        List<Map<String, Object>> events =
                store.eventsAfter(Data.str(session, "id"), verified).stream()
                        .map(StoredRecordDO::toBoundary)
                        .toList();
        long receiveGap = now - Data.num(session, "lastReceivedMs", now);
        long budget =
                receiveGap <= MAX_RECEIVE_GAP_MS
                        ? receiveGap + Data.num(session, "carriedBudgetMs", 0)
                        : 0;
        Map<String, Object> previous = Data.obj(session.get("previousSample"));
        if (receiveGap > MAX_RECEIVE_GAP_MS || previous.isEmpty()) {
            previous = Map.of();
            budget = 0;
            session.put("speedWindow", List.of());
        }
        Map<String, Object> progress =
                store.lock(
                                Store.Table.LEARNING_PROGRESS,
                                Data.str(
                                        store.byKey(
                                                        Store.Table.LEARNING_PROGRESS,
                                                        session.get("assignmentId")
                                                                + ":"
                                                                + item.get("id"))
                                                .toBoundary(),
                                        "id"))
                        .toBoundary();
        boolean pendingGap = false;
        for (Map<String, Object> event : events) {
            long seq = Data.num(event, "seq", 0);
            if (seq <= verified) {
                continue;
            }
            if (seq != verified + 1) {
                session.putIfAbsent("pendingGapSince", now);
                if (now - Data.num(session, "pendingGapSince", now) <= MAX_RECEIVE_GAP_MS) {
                    pendingGap = true;
                    break;
                }
                List<Object> closed = new ArrayList<>(Data.list(session.get("closedGapRanges")));
                closed.add(List.of(verified + 1, seq - 1));
                session.put("closedGapRanges", closed);
                previous = Map.of();
                budget = 0;
            }
            boolean video = "MP4".equals(item.get("type"));
            boolean boundary =
                    List.of("SEEK", "HIDE", "WAITING", "PAGE_LEAVE", "CLOSE")
                            .contains(Data.str(event, "type"));
            if (boundary) {
                previous = Map.of();
                session.put("speedWindow", List.of());
            }
            boolean speedAllowed = !video || speedAllowed(session, previous, event);
            long advance =
                    previous.isEmpty()
                            ? 0
                            : Math.max(
                                    0,
                                    Data.num(Data.obj(event.get("payload")), "positionMs", 0)
                                            - Data.num(
                                                    Data.obj(previous.get("payload")),
                                                    "positionMs",
                                                    0));
            long totalAdvance = Data.num(session, "admittedAdvanceMs", 0);
            long tolerance = Math.min(1000, Data.num(item, "durationMs", 0) / 100);
            boolean wallAllowed =
                    !video
                            || totalAdvance + advance
                                    <= now - Data.num(session, "startedMs", now) + tolerance;
            long used =
                    speedAllowed && wallAllowed
                            ? LearningMath.apply(progress, item, previous, event, budget)
                            : 0;
            if (video && used > 0) {
                session.put("admittedAdvanceMs", totalAdvance + advance);
            }
            budget -= used;
            event.putAll(
                    Data.map(
                            "decision",
                            "VERIFIED",
                            "counted",
                            used > 0,
                            "reason",
                            used > 0 ? "COUNTED" : "BASELINE_OR_INVALID"));
            store.save(Store.Table.LEARNING_EVENT, event, "VERIFIED");
            results.stream()
                    .filter(result -> Data.num(result, "seq", 0) == seq)
                    .forEach(
                            result -> {
                                result.put("counted", used > 0);
                                result.put("reason", event.get("reason"));
                            });
            previous = boundary ? Map.of() : event;
            verified = seq;
            session.remove("pendingGapSince");
        }
        session.putAll(
                Data.map(
                        "previousSample",
                        previous,
                        "verifiedSeq",
                        verified,
                        "carriedBudgetMs",
                        pendingGap ? budget : 0L));
        long received = Data.num(session, "receivedSeq", 0);
        while (received < Data.num(session, "maxReceivedSeq", received)
                && store.findKey(
                                Store.Table.LEARNING_EVENT,
                                session.get("id") + ":" + (received + 1))
                        .isPresent()) {
            received++;
        }
        session.put("receivedSeq", received);
        store.save(
                Store.Table.LEARNING_PROGRESS,
                progress,
                Data.bool(progress, "requirementsMet") ? "SATISFIED" : "IN_PROGRESS");
    }

    private boolean speedAllowed(
            Map<String, Object> session, Map<String, Object> previous, Map<String, Object> event) {
        if (previous.isEmpty()) {
            return true;
        }
        long elapsed = Data.num(event, "elapsedMs", 0);
        long dt = elapsed - Data.num(previous, "elapsedMs", 0);
        long dp =
                Data.num(Data.obj(event.get("payload")), "positionMs", 0)
                        - Data.num(Data.obj(previous.get("payload")), "positionMs", 0);
        if (dt <= 0 || dp <= 0 || dt > 3000) {
            return false;
        }
        List<Object> window = new ArrayList<>(Data.list(session.get("speedWindow")));
        window.removeIf(raw -> elapsed - Data.num(Data.obj(raw), "at", 0) > 30000);
        long advanced = window.stream().mapToLong(raw -> Data.num(Data.obj(raw), "dp", 0)).sum();
        long duration = window.stream().mapToLong(raw -> Data.num(Data.obj(raw), "dt", 0)).sum();
        boolean allowed = advanced + dp <= duration + dt + 1000;
        window.add(Data.map("at", elapsed, "dp", dp, "dt", dt));
        session.put("speedWindow", window);
        return allowed;
    }

    private void updateAssignment(Map<String, Object> assignment) {
        if ("COMPLETED".equals(assignment.get("status"))) {
            return;
        }
        boolean satisfied =
                store.refs(Store.Table.LEARNING_PROGRESS, Data.str(assignment, "id")).stream()
                        .map(StoredRecordDO::toBoundary)
                        .toList()
                        .stream()
                        .allMatch(progress -> Data.bool(progress, "requirementsMet"));
        assignment.putAll(
                Data.map(
                        "status",
                        satisfied ? "READY_TO_CONFIRM" : "IN_PROGRESS",
                        "lastLearningAt",
                        Data.now()));
        store.save(Store.Table.ASSIGNMENT, assignment, Data.str(assignment, "status"));
    }

    private Map<String, Object> response(
            Map<String, Object> session,
            Map<String, Object> item,
            List<Map<String, Object>> results) {
        Map<String, Object> progress =
                store.byKey(
                                Store.Table.LEARNING_PROGRESS,
                                session.get("assignmentId") + ":" + item.get("id"))
                        .toBoundary();
        long received = Data.num(session, "receivedSeq", 0);
        long verified = Data.num(session, "verifiedSeq", 0);
        List<Map<String, Object>> records =
                store.eventsAfter(Data.str(session, "id"), verified).stream()
                        .map(StoredRecordDO::toBoundary)
                        .toList();
        long maximum = Data.num(session, "maxReceivedSeq", received);
        List<Object> missing = new ArrayList<>();
        for (long seq = Math.max(received, verified) + 1; seq < maximum; seq++) {
            final long sought = seq;
            if (records.stream().noneMatch(row -> Data.num(row, "seq", 0) == sought)) {
                missing.add(List.of(seq, seq));
            }
        }
        return Data.map(
                "sessionId",
                session.get("id"),
                "sessionState",
                session.get("state"),
                "sessionValid",
                "ACTIVE".equals(session.get("state")),
                "expiresAt",
                Instant.ofEpochMilli(Data.num(session, "expiresMs", 0)).toString(),
                "nextSeq",
                maximum + 1,
                "receivedSeq",
                received,
                "receivedThroughSeq",
                received,
                "verifiedSeq",
                session.get("verifiedSeq"),
                "missingSeqRanges",
                missing,
                "closedGapRanges",
                session.getOrDefault("closedGapRanges", List.of()),
                "confirmedProgress",
                tasks.progressView(progress, item),
                "eventResults",
                results);
    }

    @Transactional
    public Map<String, Object> close(String id, Map<String, Object> actor) {
        Map<String, Object> binding = auth.binding(actor, true);
        Map<String, Object> session = store.lock(Store.Table.LEARNING_SESSION, id).toBoundary();
        Data.require(
                binding.get("personId").equals(session.get("personId")),
                "FORBIDDEN",
                "Not your session");
        session.put("state", "CLOSED");
        store.save(Store.Table.LEARNING_SESSION, session, "CLOSED");
        return Data.map("sessionId", id, "sessionState", "CLOSED");
    }
}
