package com.yongsen.training;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;

/** Opt-in against only the disposable local MySQL database created by verify-mysql.mjs. */
class BackgroundWorkMysqlTest {
    @Test
    void mysqlPaginationKeepsOldFailuresAheadOfMoreThanOneHundredCompletedJobs() {
        String url = System.getProperty("training.mysql.test-url");
        assumeTrue(url != null, "Run by isolated MySQL verification only");
        assertTrue(
                url.matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/training_check(\\?.*)?"),
                "Only the isolated loopback training_check database is permitted");
        DriverManagerDataSource dataSource = new DriverManagerDataSource(url, "root", "");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        Clock clock = mock(Clock.class);
        when(clock.millis()).thenReturn(1000000L);
        BackgroundWorkStore jobs = new BackgroundWorkStore(jdbc, clock);
        String prefix = "mysql-page-" + Data.id();
        java.util.List<String> created = new java.util.ArrayList<>();
        try {
            String failedId = jobs.enqueue("MEDIA", prefix + "-failed");
            created.add(failedId);
            jdbc.update("UPDATE background_work SET status = 'FAILED' WHERE id = ?", failedId);
            when(clock.millis()).thenReturn(2000000L);
            for (int index = 0; index < 102; index++) {
                String id = jobs.enqueue("MEDIA", prefix + "-done-" + index);
                created.add(id);
                jdbc.update("UPDATE background_work SET status = 'DONE' WHERE id = ?", id);
            }
            assertEquals(failedId, jobs.list(100, 0).get(0).id());
            java.util.Set<String> visited = new java.util.HashSet<>();
            jobs.list(100, 0).forEach(row -> visited.add(row.id()));
            jobs.list(100, 100).forEach(row -> visited.add(row.id()));
            assertTrue(visited.containsAll(created));
            assertEquals(3, jobs.list(100, 100).size());
        } finally {
            for (String id : created) {
                jdbc.update("DELETE FROM background_work WHERE id = ?", id);
            }
        }
    }

    @Test
    void durableWorkTransitionsUseRealMysqlTransactionsAndUpsertSemantics() {
        String url = System.getProperty("training.mysql.test-url");
        assumeTrue(url != null, "Run by isolated MySQL verification only");
        assertTrue(
                url.matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/training_check(\\?.*)?"),
                "Only the isolated loopback training_check database is permitted");
        DriverManagerDataSource dataSource = new DriverManagerDataSource(url, "root", "");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        TransactionTemplate transactions =
                new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        Clock clock = mock(Clock.class);
        when(clock.millis()).thenReturn(1000000L);
        BackgroundWorkStore jobs = new BackgroundWorkStore(jdbc, clock);
        String target = "mysql-test-" + Data.id();
        String id = Data.hash("MEDIA:" + target);
        try {
            transactions.executeWithoutResult(
                    status -> {
                        jobs.enqueue("MEDIA", target);
                        status.setRollbackOnly();
                    });
            assertEquals(
                    0,
                    jdbc.queryForObject(
                            "SELECT COUNT(id) FROM background_work WHERE id = ?",
                            Integer.class,
                            id));
            transactions.executeWithoutResult(
                    status -> {
                        jobs.enqueue("MEDIA", target);
                        jobs.enqueue("MEDIA", target);
                    });
            assertEquals(2, jobs.get(id, false).generation());
            BackgroundWorkStore.Work first = transactions.execute(status -> jobs.claim(id));
            assertEquals("BUSY", transactions.execute(status -> jobs.claim(id)).status());
            jobs.enqueue("MEDIA", target);
            assertEquals("RUNNING", jobs.get(id, false).status());
            assertEquals(
                    Boolean.TRUE,
                    transactions.execute(status -> jobs.finish(first, "cursor", false)));
            BackgroundWorkStore.Work second = transactions.execute(status -> jobs.claim(id));
            assertEquals("", second.cursor());
            when(clock.millis()).thenReturn(3000000L);
            BackgroundWorkStore.Work recovered = transactions.execute(status -> jobs.claim(id));
            assertFalse(recovered.leaseToken().equals(second.leaseToken()));
            assertEquals(
                    Boolean.FALSE, transactions.execute(status -> jobs.finish(second, "", false)));
            assertEquals("RUNNING", jobs.get(id, false).status());
            assertEquals(
                    Boolean.FALSE,
                    transactions.execute(status -> jobs.finish(recovered, "", false)));
            assertEquals("DONE", jobs.get(id, false).status());
        } finally {
            jdbc.update("DELETE FROM background_work WHERE id = ?", id);
        }
    }
}
