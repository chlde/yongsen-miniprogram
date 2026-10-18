package com.yongsen.training;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

class TaskMaterialOrderTest {
    @Test
    void publicationPersistsSelectedOrderAndDetailUsesIt() {
        BackendFixture fixture = new BackendFixture();
        TaskService tasks =
                new TaskService(
                        fixture.store, mock(AuthService.class), mock(NotificationService.class));
        Map<String, Object> first = material(fixture, "先看视频");
        Map<String, Object> second = material(fixture, "后看视频");
        Map<String, Object> task =
                tasks.create(
                        Data.map(
                                "title",
                                "材料顺序",
                                "personIds",
                                List.of(fixture.person.get("id")),
                                "materials",
                                List.of(
                                        Data.map("materialId", second.get("id")),
                                        Data.map("materialId", first.get("id")))),
                        "admin");
        tasks.publish(Data.str(task, "id"), "publish-order", "admin");
        List<Map<String, Object>> ordered = tasks.materials(Data.str(task, "id"));
        assertEquals(second.get("id"), ordered.get(0).get("materialId"));
        assertEquals(first.get("id"), ordered.get(1).get("materialId"));
        assertEquals(0, Data.num(ordered.get(0), "sortOrder", -1));
        assertEquals(1, Data.num(ordered.get(1), "sortOrder", -1));
        Map<String, Object> assignment =
                fixture.store
                        .refs(Store.Table.ASSIGNMENT, Data.str(task, "id"))
                        .get(0)
                        .toBoundary();
        Map<String, Object> detail =
                tasks.detail(Data.str(assignment, "id"), Data.map("role", "ADMIN"));
        assertEquals(
                second.get("id"),
                Data.obj(Data.list(detail.get("materials")).get(0)).get("materialId"));
    }

    private Map<String, Object> material(BackendFixture fixture, String title) {
        return fixture.store.create(
                Store.Table.MATERIAL,
                Data.map(
                        "title",
                        title,
                        "type",
                        "MP4",
                        "durationMs",
                        12000L,
                        "status",
                        "READY",
                        "compatibilityChecked",
                        true),
                null,
                null,
                "READY",
                null);
    }
}
