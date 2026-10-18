package com.yongsen.training;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Clock;
import java.util.concurrent.atomic.AtomicBoolean;

class BackgroundWorkTest {
    private BackendFixture fixture;
    private BackgroundWorkStore jobs;
    private BackgroundWorkDispatcher dispatcher;
    private BackgroundInvoker invoker;
    private MediaService media;
    private NotificationService notifications;
    private Clock clock;
    private MockEnvironment environment;

    @BeforeEach
    void setup() {
        fixture = new BackendFixture();
        clock = mock(Clock.class);
        when(clock.millis()).thenReturn(1000000L);
        DataSourceTransactionManager manager =
                (DataSourceTransactionManager) fixture.transactions.getTransactionManager();
        jobs = new BackgroundWorkStore(new JdbcTemplate(manager.getDataSource()), clock);
        invoker = mock(BackgroundInvoker.class);
        media = mock(MediaService.class);
        notifications = mock(NotificationService.class);
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("media", media);
        beans.addBean("notifications", notifications);
        environment =
                new MockEnvironment()
                        .withProperty("TRAINING_BACKGROUND_MODE", "manual")
                        .withProperty(
                                "TRAINING_WORKER_SECRET", "test-only-worker-secret-32-characters");
        environment.setActiveProfiles("test");
        dispatcher =
                new BackgroundWorkDispatcher(
                        jobs,
                        fixture.transactions,
                        invoker,
                        environment,
                        beans.getBeanProvider(MediaService.class),
                        beans.getBeanProvider(NotificationService.class));
    }

    private String enqueue(String kind, String target) {
        dispatcher.scheduleAfterCommit(kind, target);
        return Data.hash(kind + ":" + target);
    }

    @Test
    void rollbackNeverInvokesAndNeverLeavesOrphanJob() {
        fixture.transactions.executeWithoutResult(
                status -> {
                    enqueue("MEDIA", "rollback");
                    status.setRollbackOnly();
                });
        assertTrue(jobs.list(10).isEmpty());
        verify(invoker, never()).invoke(anyString());
    }

    @Test
    void commitCoalescesRepeatedWakesButRetainsGeneration() {
        fixture.transactions.executeWithoutResult(
                status -> {
                    enqueue("NOTIFICATIONS", "all");
                    enqueue("NOTIFICATIONS", "all");
                    enqueue("NOTIFICATIONS", "all");
                    verify(invoker, never()).invoke(anyString());
                });
        String id = Data.hash("NOTIFICATIONS:all");
        assertEquals(3, jobs.get(id, false).generation());
        verify(invoker, times(1)).invoke(id);
    }

    @Test
    void duplicateDeliveredEventDoesNotExecuteCompletedMediaAgain() {
        String id = enqueue("MEDIA", "material");
        assertEquals("done", dispatcher.run(id));
        assertEquals("done", dispatcher.run(id));
        verify(media, times(1)).processOne("material");
    }

    @Test
    void activeLeaseStopsConcurrentWorkerAndOldTokenCannotFinishAfterExpiry() {
        String id = enqueue("MEDIA", "material");
        BackgroundWorkStore.Work first = fixture.transactions.execute(status -> jobs.claim(id));
        assertEquals("busy", dispatcher.run(id));
        verify(media, never()).processOne(anyString());
        when(clock.millis()).thenReturn(3000000L);
        BackgroundWorkStore.Work second = fixture.transactions.execute(status -> jobs.claim(id));
        assertEquals(
                Boolean.FALSE,
                fixture.transactions.execute(status -> jobs.finish(first, "", false)));
        assertEquals(second.leaseToken(), jobs.get(id, false).leaseToken());
        assertEquals(
                Boolean.FALSE,
                fixture.transactions.execute(status -> jobs.finish(second, "", false)));
        assertEquals("DONE", jobs.get(id, false).status());
    }

    @Test
    void wakeDuringExecutionProducesAnotherInvocationWithoutLosingNewGeneration() {
        String id = enqueue("MEDIA", "material");
        AtomicBoolean first = new AtomicBoolean(true);
        doAnswer(
                        invocation -> {
                            if (first.getAndSet(false)) {
                                enqueue("MEDIA", "material");
                            }
                            return null;
                        })
                .when(media)
                .processOne("material");
        assertEquals("done", dispatcher.run(id));
        assertEquals("DISPATCHED", jobs.get(id, false).status());
        assertEquals("done", dispatcher.run(id));
        assertEquals("DONE", jobs.get(id, false).status());
        assertEquals(2, jobs.get(id, false).generation());
    }

    @Test
    void invocationFailurePersistsRecoverableStateAndDoesNotUndoBusinessCommit() {
        doThrow(new IllegalStateException("must not be persisted"))
                .when(invoker)
                .invoke(anyString());
        String id = enqueue("MEDIA", "material");
        assertEquals("RETRY", jobs.get(id, false).status());
        assertEquals("DISPATCH_FAILED", jobs.get(id, false).errorCode());
        assertEquals("done", dispatcher.run(id));
    }

    @Test
    void repeatedExecutionFailuresAreBoundedAndAdministratorCanRecover() {
        doThrow(new BusinessException("COS_TEMPORARY", "Temporary", 503))
                .when(media)
                .processOne(anyString());
        String id = enqueue("MEDIA", "material");
        for (int attempt = 0; attempt < 4; attempt++) {
            assertEquals("retry", dispatcher.run(id));
        }
        assertEquals("failed", dispatcher.run(id));
        assertEquals("failed", dispatcher.run(id));
        verify(media, times(5)).processOne("material");
        dispatcher.retry(id);
        assertEquals(0, jobs.get(id, false).attempts());
        assertEquals("DISPATCHED", jobs.get(id, false).status());
    }

    @Test
    void boundedBatchPersistsCursorAcrossInvocations() {
        when(notifications.dispatchBatch("all", ""))
                .thenReturn(new NotificationService.DispatchBatch("n5", true));
        when(notifications.dispatchBatch("all", "n5"))
                .thenReturn(new NotificationService.DispatchBatch("n6", false));
        String id = enqueue("NOTIFICATIONS", "all");
        assertEquals("done", dispatcher.run(id));
        assertEquals("n5", jobs.get(id, false).cursor());
        assertEquals("done", dispatcher.run(id));
        assertEquals("DONE", jobs.get(id, false).status());
        verify(notifications).dispatchBatch("all", "n5");
    }

    @Test
    void workerRejectsMissingWrongAndUserAuthenticationBeforeExecuting() throws Exception {
        AuthService auth = mock(AuthService.class);
        BackgroundWorkController controller =
                new BackgroundWorkController(dispatcher, jobs, auth, fixture.store, environment);
        MockMvc mvc =
                MockMvcBuilders.standaloneSetup(controller)
                        .setControllerAdvice(new ApiAdvice())
                        .build();
        String id = enqueue("MEDIA", "material");
        mvc.perform(post("/internal/background-work/" + id + "/run"))
                .andExpect(status().isForbidden());
        mvc.perform(
                        post("/internal/background-work/" + id + "/run")
                                .header("Authorization", "Bearer user-token"))
                .andExpect(status().isForbidden());
        verify(media, never()).processOne(anyString());
        mvc.perform(
                        post("/internal/background-work/" + id + "/run")
                                .header(
                                        "Authorization",
                                        "Bearer test-only-worker-secret-32-characters"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("done"));
    }

    @Test
    void administratorListOmitsWorkerLeaseTokenAndRequiresAdministrator() throws Exception {
        AuthService auth = mock(AuthService.class);
        when(auth.admin(org.mockito.ArgumentMatchers.any()))
                .thenThrow(new BusinessException("FORBIDDEN", "Admin required", 403));
        MockMvc mvc =
                MockMvcBuilders.standaloneSetup(
                                new BackgroundWorkController(
                                        dispatcher, jobs, auth, fixture.store, environment))
                        .setControllerAdvice(new ApiAdvice())
                        .build();
        mvc.perform(get("/api/v1/admin/background-work")).andExpect(status().isForbidden());
        verify(auth).admin(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void oldFailedWorkRemainsVisibleAndPaginationReachesCompletedTail() throws Exception {
        DataSourceTransactionManager manager =
                (DataSourceTransactionManager) fixture.transactions.getTransactionManager();
        JdbcTemplate jdbc = new JdbcTemplate(manager.getDataSource());
        String failedId = jobs.enqueue("MEDIA", "old-failed");
        jdbc.update("UPDATE background_work SET status = 'FAILED' WHERE id = ?", failedId);
        when(clock.millis()).thenReturn(2000000L);
        for (int index = 0; index < 102; index++) {
            String id = jobs.enqueue("MEDIA", "done-" + index);
            jdbc.update("UPDATE background_work SET status = 'DONE' WHERE id = ?", id);
        }
        AuthService auth = mock(AuthService.class);
        MockMvc mvc =
                MockMvcBuilders.standaloneSetup(
                                new BackgroundWorkController(
                                        dispatcher, jobs, auth, fixture.store, environment))
                        .setControllerAdvice(new ApiAdvice())
                        .build();
        mvc.perform(get("/api/v1/admin/background-work?limit=100&offset=0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(100))
                .andExpect(jsonPath("$.data.items[0].id").value(failedId))
                .andExpect(jsonPath("$.data.items[0].leaseToken").doesNotExist())
                .andExpect(jsonPath("$.data.hasMore").value(true));
        mvc.perform(get("/api/v1/admin/background-work?limit=100&offset=100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(3))
                .andExpect(jsonPath("$.data.hasMore").value(false));
        java.util.Set<String> seen = new java.util.HashSet<>();
        jobs.list(100, 0).forEach(row -> seen.add(row.id()));
        jobs.list(100, 100).forEach(row -> seen.add(row.id()));
        assertEquals(103, seen.size());
    }

    @Test
    void paginationRejectsFractionalNegativeAndOversizedValues() throws Exception {
        MockMvc mvc =
                MockMvcBuilders.standaloneSetup(
                                new BackgroundWorkController(
                                        dispatcher,
                                        jobs,
                                        mock(AuthService.class),
                                        fixture.store,
                                        environment))
                        .setControllerAdvice(new ApiAdvice())
                        .build();
        for (String query :
                new String[] {
                    "limit=0", "limit=101", "limit=1.5", "offset=-1", "offset=100001", "offset=no"
                }) {
            mvc.perform(get("/api/v1/admin/background-work?" + query))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_PAGINATION"));
        }
    }

    @Test
    void weakWorkerSecretFailsClosed() {
        environment.setProperty("TRAINING_WORKER_SECRET", "short");
        assertThrows(
                IllegalStateException.class,
                () ->
                        new BackgroundWorkController(
                                dispatcher,
                                jobs,
                                mock(AuthService.class),
                                fixture.store,
                                environment));
    }
}
