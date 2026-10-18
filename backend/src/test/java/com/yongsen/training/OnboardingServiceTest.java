package com.yongsen.training;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Path;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

class OnboardingServiceTest {
    private static final String ADMIN_ID = "admin-onboarding-test";
    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-10-17T02:03:04.123456Z"), ZoneOffset.UTC);
    private BackendFixture fixture;
    private OnboardingService service;

    @BeforeEach
    void setup() {
        fixture = new BackendFixture();
        createAdmin(fixture.store, ADMIN_ID);
        service = new OnboardingService(fixture.store, fixture.transactions, CLOCK);
    }

    @Test
    void firstReadDoesNotWriteAndPreferencesDoNotChangeTrainingOrNotificationConfiguration() {
        Map<String, Object> notification =
                fixture.store.get(Store.Table.APP_CONFIG, "notification").toBoundary();
        Map<String, Object> administrator =
                fixture.store.get(Store.Table.ADMIN_USER, ADMIN_ID).toBoundary();
        OnboardingService.Progress initial = service.getProgress(ADMIN_ID);
        assertEquals(OnboardingService.Status.NOT_STARTED, initial.status());
        assertNull(initial.updatedAt());
        assertEquals(1, fixture.store.all(Store.Table.APP_CONFIG).size());
        service.updateProgress(ADMIN_ID, update(OnboardingService.Status.DISMISSED));
        assertEquals(
                notification,
                fixture.store.get(Store.Table.APP_CONFIG, "notification").toBoundary());
        assertEquals(
                administrator, fixture.store.get(Store.Table.ADMIN_USER, ADMIN_ID).toBoundary());
        assertEquals(1, fixture.store.all(Store.Table.ASSIGNMENT).size());
        assertEquals(0, fixture.store.all(Store.Table.NOTIFICATION).size());
        assertEquals(0, fixture.store.all(Store.Table.LEARNING_EVENT).size());
    }

    @Test
    void duplicateWritesPreserveTimestampAndRevisionAndCompletionCannotBeDowngraded() {
        OnboardingService.Progress first =
                service.updateProgress(ADMIN_ID, update(OnboardingService.Status.DISMISSED));
        StoredRecordDO firstRow = fixture.store.refs(Store.Table.APP_CONFIG, ADMIN_ID).get(0);
        OnboardingService later =
                new OnboardingService(
                        fixture.store,
                        fixture.transactions,
                        Clock.offset(CLOCK, java.time.Duration.ofHours(1)));
        assertEquals(
                first, later.updateProgress(ADMIN_ID, update(OnboardingService.Status.DISMISSED)));
        assertEquals(
                firstRow.rowVersion(),
                fixture.store.get(Store.Table.APP_CONFIG, firstRow.id()).rowVersion());
        OnboardingService.Progress completed =
                later.updateProgress(ADMIN_ID, update(OnboardingService.Status.COMPLETED));
        long completedRevision =
                fixture.store.get(Store.Table.APP_CONFIG, firstRow.id()).rowVersion();
        assertEquals(OnboardingService.Status.COMPLETED, completed.status());
        assertEquals(
                completed,
                service.updateProgress(ADMIN_ID, update(OnboardingService.Status.DISMISSED)));
        assertEquals(
                completed,
                service.updateProgress(ADMIN_ID, update(OnboardingService.Status.COMPLETED)));
        assertEquals(
                completedRevision,
                fixture.store.get(Store.Table.APP_CONFIG, firstRow.id()).rowVersion());
        assertEquals("2026-10-17T03:03:04.123Z", completed.updatedAt());
    }

    @Test
    void stateIsIsolatedByAdministratorAndInvalidInputsNeverCreateRows() {
        String otherAdmin = "other-administrator";
        createAdmin(fixture.store, otherAdmin);
        service.updateProgress(ADMIN_ID, update(OnboardingService.Status.COMPLETED));
        assertEquals(
                OnboardingService.Status.NOT_STARTED, service.getProgress(otherAdmin).status());
        List<OnboardingService.UpdateRequest> invalid =
                List.of(
                        new OnboardingService.UpdateRequest("other-tutorial", "COMPLETED"),
                        new OnboardingService.UpdateRequest(null, "COMPLETED"),
                        new OnboardingService.UpdateRequest(
                                OnboardingService.TUTORIAL_ID, "NOT_STARTED"),
                        new OnboardingService.UpdateRequest(
                                OnboardingService.TUTORIAL_ID, "completed"),
                        new OnboardingService.UpdateRequest(OnboardingService.TUTORIAL_ID, null));
        for (OnboardingService.UpdateRequest input : invalid) {
            BusinessException error =
                    assertThrows(
                            BusinessException.class,
                            () -> service.updateProgress(otherAdmin, input));
            assertEquals(400, error.status());
        }
        assertThrows(BusinessException.class, () -> service.updateProgress(otherAdmin, null));
        assertTrue(fixture.store.refs(Store.Table.APP_CONFIG, otherAdmin).isEmpty());
    }

    @Test
    void concurrentFirstWritesProduceOneRowAndCompletionWins() throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger threadNumber = new AtomicInteger();
        ThreadPoolExecutor executor =
                new ThreadPoolExecutor(
                        2,
                        2,
                        0L,
                        TimeUnit.MILLISECONDS,
                        new ArrayBlockingQueue<>(2),
                        task ->
                                new Thread(
                                        task, "onboarding-test-" + threadNumber.incrementAndGet()),
                        new ThreadPoolExecutor.AbortPolicy());
        try {
            Future<OnboardingService.Progress> dismissed =
                    executor.submit(
                            () -> {
                                ready.countDown();
                                assertTrue(start.await(5, TimeUnit.SECONDS));
                                return service.updateProgress(
                                        ADMIN_ID, update(OnboardingService.Status.DISMISSED));
                            });
            Future<OnboardingService.Progress> completed =
                    executor.submit(
                            () -> {
                                ready.countDown();
                                assertTrue(start.await(5, TimeUnit.SECONDS));
                                return service.updateProgress(
                                        ADMIN_ID, update(OnboardingService.Status.COMPLETED));
                            });
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            assertNotNull(dismissed.get(5, TimeUnit.SECONDS));
            assertNotNull(completed.get(5, TimeUnit.SECONDS));
            assertEquals(1, fixture.store.refs(Store.Table.APP_CONFIG, ADMIN_ID).size());
            assertEquals(
                    OnboardingService.Status.COMPLETED, service.getProgress(ADMIN_ID).status());
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void preferenceSurvivesClosingAndReopeningFileDatabase(@TempDir Path directory)
            throws Exception {
        String url =
                "jdbc:h2:file:"
                        + directory.resolve("onboarding")
                        + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_ON_EXIT=FALSE";
        DriverManagerDataSource datasource = new DriverManagerDataSource(url, "sa", "");
        OnboardingService.Progress saved;
        try (Connection keepAlive = datasource.getConnection()) {
            assertFalseClosed(keepAlive);
            new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(datasource);
            Store firstStore = new Store(new JdbcTemplate(datasource));
            createAdmin(firstStore, ADMIN_ID);
            TransactionTemplate transactions =
                    new TransactionTemplate(new DataSourceTransactionManager(datasource));
            saved =
                    new OnboardingService(firstStore, transactions, CLOCK)
                            .updateProgress(ADMIN_ID, update(OnboardingService.Status.COMPLETED));
        }
        DriverManagerDataSource reopened = new DriverManagerDataSource(url, "sa", "");
        Store reloadedStore = new Store(new JdbcTemplate(reopened));
        OnboardingService reloaded =
                new OnboardingService(
                        reloadedStore,
                        new TransactionTemplate(new DataSourceTransactionManager(reopened)),
                        CLOCK);
        assertEquals(saved, reloaded.getProgress(ADMIN_ID));
    }

    @Test
    void storageFailurePropagatesInsteadOfPretendingTutorialHasNotStarted() {
        Store unavailable = mock(Store.class);
        String id = Data.hash("admin-onboarding:" + ADMIN_ID + ":" + OnboardingService.TUTORIAL_ID);
        when(unavailable.maybe(Store.Table.APP_CONFIG, id))
                .thenThrow(new DataAccessResourceFailureException("database unavailable"));
        OnboardingService failing = new OnboardingService(unavailable, fixture.transactions, CLOCK);
        assertThrows(DataAccessResourceFailureException.class, () -> failing.getProgress(ADMIN_ID));
    }

    private static void assertFalseClosed(Connection connection) throws Exception {
        assertTrue(!connection.isClosed());
    }

    private static OnboardingService.UpdateRequest update(OnboardingService.Status status) {
        return new OnboardingService.UpdateRequest(OnboardingService.TUTORIAL_ID, status.name());
    }

    private static void createAdmin(Store store, String id) {
        store.create(
                Store.Table.ADMIN_USER,
                Data.map(
                        "id",
                        id,
                        "username",
                        id,
                        "passwordHash",
                        "unused-in-service-tests",
                        "status",
                        "ACTIVE"),
                null,
                null,
                "ACTIVE",
                id);
    }
}
