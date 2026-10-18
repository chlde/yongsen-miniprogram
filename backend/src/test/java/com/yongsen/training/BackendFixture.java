package com.yongsen.training;

import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;

final class BackendFixture {
    final Store store;
    final TransactionTemplate transactions;
    final Map<String, Object> person;
    final Map<String, Object> binding;
    final Map<String, Object> assignment;
    final Map<String, Object> item;

    BackendFixture() {
        DriverManagerDataSource datasource =
                new DriverManagerDataSource(
                        "jdbc:h2:mem:"
                                + Data.id()
                                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                        "sa",
                        "");
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(datasource);
        store = new Store(new JdbcTemplate(datasource));
        transactions = new TransactionTemplate(new DataSourceTransactionManager(datasource));
        person =
                store.create(
                        Store.Table.PERSON,
                        Data.map("name", "测试学员", "personNo", "T1", "status", "ACTIVE"),
                        null,
                        null,
                        "ACTIVE",
                        "T1");
        binding =
                store.create(
                        Store.Table.WX_IDENTITY_BINDING,
                        Data.map(
                                "personId",
                                person.get("id"),
                                "identityId",
                                Data.hash("test-app:openid-1"),
                                "status",
                                "ACTIVE",
                                "checkedAt",
                                Data.now()),
                        Data.hash("test-app:openid-1"),
                        Data.str(person, "id"),
                        "ACTIVE",
                        "person:" + person.get("id"));
        Map<String, Object> task =
                store.create(
                        Store.Table.TASK,
                        Data.map("title", "测试任务", "status", "ACTIVE"),
                        null,
                        null,
                        "ACTIVE",
                        null);
        assignment =
                store.create(
                        Store.Table.ASSIGNMENT,
                        Data.map(
                                "taskId",
                                task.get("id"),
                                "personId",
                                person.get("id"),
                                "status",
                                "NOT_STARTED"),
                        Data.str(task, "id"),
                        Data.str(person, "id"),
                        "NOT_STARTED",
                        task.get("id") + ":" + person.get("id"));
        item =
                store.create(
                        Store.Table.TASK_MATERIAL,
                        Data.map(
                                "taskId",
                                task.get("id"),
                                "materialId",
                                "material-1",
                                "type",
                                "MP4",
                                "durationMs",
                                12000L,
                                "rule",
                                Data.map("playbackRate", 1)),
                        Data.str(task, "id"),
                        "material-1",
                        "ACTIVE",
                        task.get("id") + ":material-1");
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
                        "requirementsMet",
                        false,
                        "coverageRatio",
                        0.0),
                Data.str(assignment, "id"),
                Data.str(item, "id"),
                "NOT_STARTED",
                assignment.get("id") + ":" + item.get("id"));
        store.create(
                Store.Table.APP_CONFIG,
                Data.map(
                        "id",
                        "notification",
                        "templateId",
                        "template-1",
                        "fieldMapping",
                        Data.map("title", "thing1")),
                null,
                null,
                "ACTIVE",
                null);
    }
}
