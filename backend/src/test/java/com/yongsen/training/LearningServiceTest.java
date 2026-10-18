package com.yongsen.training;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

class LearningServiceTest {
    private BackendFixture fixture;
    private LearningService service;
    private MutableClock clock;
    private String sessionId;
    private Map<String, Object> actor;

    @BeforeEach
    void setup() {
        fixture = new BackendFixture();
        AuthService auth = mock(AuthService.class);
        when(auth.binding(anyMap(), eq(true))).thenReturn(fixture.binding);
        TaskService tasks = new TaskService(fixture.store, auth, mock(NotificationService.class));
        clock = new MutableClock();
        service = new LearningService(fixture.store, auth, tasks, clock);
        actor = Data.map("role", "LEARNER", "actorId", "identity-1");
        sessionId =
                Data.str(
                        service.start(
                                actor,
                                Data.map(
                                        "assignmentId",
                                        fixture.assignment.get("id"),
                                        "taskMaterialId",
                                        fixture.item.get("id")),
                                null),
                        "sessionId");
    }

    @Test
    void repeatedSmallSpeedToleranceCannotCompleteTwelveSecondsInTenSeconds() {
        send(1, 0, 0, "VIDEO_SAMPLE");
        Map<String, Object> result = Map.of();
        for (int index = 1; index <= 10; index++) {
            clock.advance(1000);
            result = send(index + 1, index * 1000L, index * 1200L, "VIDEO_SAMPLE");
        }
        Map<String, Object> progress = Data.obj(result.get("confirmedProgress"));
        assertFalse(Data.bool(progress, "requirementsMet"));
        assertTrue(Data.num(progress, "effectiveMs", 0) <= 10000);
    }

    @Test
    void explicitSeekStartsNewBaselineAndAllowsImmediateNormalRemedialViewing() {
        send(1, 0, 0, "VIDEO_SAMPLE");
        clock.advance(1000);
        send(2, 1000, 10000, "SEEK");
        clock.advance(1000);
        send(3, 2000, 0, "SEEK");
        clock.advance(1000);
        send(4, 3000, 0, "VIDEO_SAMPLE");
        clock.advance(1000);
        Map<String, Object> result = send(5, 4000, 1000, "VIDEO_SAMPLE");
        Map<String, Object> progress = Data.obj(result.get("confirmedProgress"));
        assertEquals(1000L, Data.num(progress, "effectiveMs", 0));
        assertEquals(List.of(List.of(0, 1000)), progress.get("coveredRanges"));
    }

    @Test
    void wallClockBudgetAppliesToActualCoverageWhenClientAcceleratesElapsedClock() {
        send(1, 0, 0, "VIDEO_SAMPLE");
        Map<String, Object> result = Map.of();
        for (int index = 1; index <= 10; index++) {
            clock.advance(1000);
            result = send(index + 1, index * 1200L, index * 1200L, "VIDEO_SAMPLE");
        }
        Map<String, Object> progress = Data.obj(result.get("confirmedProgress"));
        assertFalse(Data.bool(progress, "requirementsMet"));
        assertTrue(LearningMath.length(Data.list(progress.get("coveredRanges"))) <= 10120);
    }

    @Test
    void expiredBatchLeavesRejectionAuditWithoutChangingEventsProgressOrLease() {
        send(1, 0, 0, "VIDEO_SAMPLE");
        Map<String, Object> before =
                fixture.store.get(Store.Table.LEARNING_SESSION, sessionId).toBoundary();
        clock.advance(31000);
        Map<String, Object> response = send(2, 31000, 12000, "VIDEO_SAMPLE");
        Map<String, Object> after =
                fixture.store.get(Store.Table.LEARNING_SESSION, sessionId).toBoundary();
        assertEquals("EXPIRED", response.get("sessionState"));
        assertEquals(before.get("expiresMs"), after.get("expiresMs"));
        assertEquals(before.get("lastReceivedMs"), after.get("lastReceivedMs"));
        assertEquals(1, fixture.store.refs(Store.Table.LEARNING_EVENT, sessionId).size());
        assertEquals(0, Data.num(Data.obj(response.get("confirmedProgress")), "effectiveMs", -1));
        Map<String, Object> audit =
                fixture.store.refs(Store.Table.AUDIT_LOG, sessionId).get(0).toBoundary();
        Map<String, Object> detail = Data.obj(audit.get("detail"));
        assertEquals("LEARNING_BATCH_REJECTED", audit.get("action"));
        assertEquals("SESSION_EXPIRED", detail.get("reason"));
        assertEquals("batch-2", detail.get("batchId"));
        assertEquals(1, Data.num(detail, "eventCount", 0));
        assertEquals(64, Data.str(detail, "batchHash").length());
    }

    private Map<String, Object> send(long seq, long elapsed, long position, String type) {
        return service.events(
                sessionId,
                actor,
                Data.map(
                        "batchId",
                        "batch-" + seq,
                        "events",
                        List.of(
                                Data.map(
                                        "seq",
                                        seq,
                                        "elapsedMs",
                                        elapsed,
                                        "type",
                                        type,
                                        "payload",
                                        Data.map(
                                                "positionMs",
                                                position,
                                                "playing",
                                                true,
                                                "playbackRate",
                                                1,
                                                "buffering",
                                                false,
                                                "appVisible",
                                                true,
                                                "pageVisible",
                                                true,
                                                "playerVisible",
                                                true)))));
    }

    private static final class MutableClock extends Clock {
        private long current = Instant.parse("2026-01-01T00:00:00Z").toEpochMilli();

        void advance(long amount) {
            current += amount;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(current);
        }
    }
}
