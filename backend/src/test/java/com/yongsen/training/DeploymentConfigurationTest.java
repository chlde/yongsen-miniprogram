package com.yongsen.training;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.crypto.encrypt.TextEncryptor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.file.Path;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

class DeploymentConfigurationTest {
    private static final String CONFIG_PATH = "/api/v1/admin/notification-config";
    private static final String LIMITS_PATH = "/api/v1/admin/materials/upload-limits";
    private static final String UPLOAD_PATH = "/api/v1/admin/materials/uploads";
    private static final long UPLOAD_CAP_BYTES = 8L * 1024 * 1024;

    @TempDir private Path directory;
    private BackendFixture fixture;
    private MockMvc mvc;

    @BeforeEach
    void setup() throws Exception {
        fixture = new BackendFixture();
        fixture.store.create(
                Store.Table.ADMIN_USER,
                Data.map("id", "administrator", "username", "administrator", "status", "ACTIVE"),
                null,
                null,
                "ACTIVE",
                "administrator");
        token("administrator", "ADMIN", "administrator");
        token("learner", "LEARNER", "learner-identity");
        WxProvider provider = mock(WxProvider.class);
        AuthService auth =
                new AuthService(
                        fixture.store,
                        provider,
                        mock(TextEncryptor.class),
                        new MockEnvironment(),
                        "unused");
        MediaService media =
                new MediaService(
                        fixture.store,
                        fixture.transactions,
                        directory.toString(),
                        "https://media.example",
                        "unused-ffprobe",
                        "unused-pdftoppm",
                        "unused-pdfinfo",
                        UPLOAD_CAP_BYTES);
        ApiController controller =
                new ApiController(
                        fixture.store,
                        auth,
                        mock(TaskService.class),
                        mock(LearningService.class),
                        mock(NotificationService.class),
                        media,
                        provider,
                        fixture.transactions);
        mvc =
                MockMvcBuilders.standaloneSetup(controller, new MediaController(auth, media))
                        .setControllerAdvice(new ApiAdvice())
                        .build();
    }

    @Test
    void titleAndOptionalDeadlineUseThingAndTimeFields() throws Exception {
        updateConfig("template_ABC-123", Data.map("title", "thing12", "dueAt", "time3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.templateId").value("template_ABC-123"))
                .andExpect(jsonPath("$.data.fieldMapping.title").value("thing12"))
                .andExpect(jsonPath("$.data.fieldMapping.dueAt").value("time3"));
        Map<String, Object> config =
                fixture.store.get(Store.Table.APP_CONFIG, "notification").toBoundary();
        assertEquals(Data.map("title", "thing12", "dueAt", "time3"), config.get("fieldMapping"));
        assertEquals(1, fixture.store.all(Store.Table.AUDIT_LOG).size());
    }

    @Test
    void titleOnlyMappingIsAcceptedWithoutInventingADeadlineField() throws Exception {
        updateConfig("template-123", Data.map("title", "thing1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.fieldMapping.title").value("thing1"))
                .andExpect(jsonPath("$.data.fieldMapping.dueAt").doesNotExist());
        assertEquals(
                Data.map("title", "thing1"),
                fixture.store
                        .get(Store.Table.APP_CONFIG, "notification")
                        .toBoundary()
                        .get("fieldMapping"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidMappings")
    void invalidMappingDoesNotModifyAnyBusinessRecord(String scenario, Object mapping)
            throws Exception {
        Map<Store.Table, List<Map<String, Object>>> before = databaseSnapshot();
        updateConfig("template-123", mapping)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_MAPPING"));
        assertEquals(before, databaseSnapshot(), scenario);
    }

    static Stream<Arguments> invalidMappings() {
        return Stream.of(
                Arguments.of("missing title", Data.map("dueAt", "time2")),
                Arguments.of("title is not thing", Data.map("title", "time1")),
                Arguments.of("zero title index", Data.map("title", "thing0")),
                Arguments.of("leading zero title index", Data.map("title", "thing01")),
                Arguments.of("date is not time", Data.map("title", "thing1", "dueAt", "date2")),
                Arguments.of(
                        "deadline is not time", Data.map("title", "thing1", "dueAt", "thing2")),
                Arguments.of("zero deadline index", Data.map("title", "thing1", "dueAt", "time0")),
                Arguments.of(
                        "unknown business key", Data.map("title", "thing1", "person", "thing2")),
                Arguments.of("null title value", Data.map("title", null)),
                Arguments.of("null deadline value", Data.map("title", "thing1", "dueAt", null)),
                Arguments.of("numeric title value", Data.map("title", 1)),
                Arguments.of("null mapping", null),
                Arguments.of("array mapping", List.of("thing1")),
                Arguments.of(
                        "too many fields",
                        Data.map("title", "thing1", "dueAt", "time2", "remark", "thing3")));
    }

    @ParameterizedTest
    @MethodSource("invalidTemplateIds")
    void invalidTemplateFormatDoesNotModifyAnyBusinessRecord(String templateId) throws Exception {
        Map<Store.Table, List<Map<String, Object>>> before = databaseSnapshot();
        updateConfig(templateId, Data.map("title", "thing1"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_TEMPLATE"));
        assertEquals(before, databaseSnapshot());
    }

    static Stream<String> invalidTemplateIds() {
        return Stream.of(
                "invalid template",
                "invalid/template",
                "invalid.template",
                "模板ID",
                "<template>",
                "a".repeat(129));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "  "})
    void missingTemplateDoesNotModifyAnyBusinessRecord(String templateId) throws Exception {
        Map<Store.Table, List<Map<String, Object>>> before = databaseSnapshot();
        updateConfig(templateId, Data.map("title", "thing1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        assertEquals(before, databaseSnapshot());
    }

    @ParameterizedTest
    @MethodSource("nonStringTemplateIds")
    void nonStringTemplateDoesNotModifyAnyBusinessRecord(Object templateId) throws Exception {
        Map<Store.Table, List<Map<String, Object>>> before = databaseSnapshot();
        updateConfig(templateId, Data.map("title", "thing1"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_TEMPLATE"));
        assertEquals(before, databaseSnapshot());
    }

    static Stream<Object> nonStringTemplateIds() {
        return Stream.of(null, 123, true, List.of("template-123"));
    }

    @Test
    void uploadLimitApiReportsTheActualConfiguredCapWithoutWrites() throws Exception {
        Map<Store.Table, List<Map<String, Object>>> before = databaseSnapshot();
        mvc.perform(get(LIMITS_PATH).header("Authorization", "Bearer administrator"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.videoMaxBytes").value(UPLOAD_CAP_BYTES))
                .andExpect(jsonPath("$.data.pdfMaxBytes").value(UPLOAD_CAP_BYTES));
        assertEquals(before, databaseSnapshot());
    }

    @ParameterizedTest
    @ValueSource(strings = {"MP4", "PDF"})
    void uploadApiRejectsOneByteAboveConfiguredCapWithoutCreatingMaterial(String type)
            throws Exception {
        Map<Store.Table, List<Map<String, Object>>> before = databaseSnapshot();
        mvc.perform(
                        post(UPLOAD_PATH)
                                .header("Authorization", "Bearer administrator")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        Data.json(
                                                Data.map(
                                                        "title",
                                                        "上传限制测试",
                                                        "type",
                                                        type,
                                                        "size",
                                                        UPLOAD_CAP_BYTES + 1))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("FILE_TOO_LARGE"));
        assertEquals(before, databaseSnapshot());
    }

    @Test
    void deploymentConfigurationAndUploadLimitsRequireAdministrator() throws Exception {
        Map<Store.Table, List<Map<String, Object>>> before = databaseSnapshot();
        String body =
                Data.json(
                        Data.map(
                                "templateId",
                                "template-123",
                                "fieldMapping",
                                Data.map("title", "thing1")));
        mvc.perform(get(LIMITS_PATH)).andExpect(status().isUnauthorized());
        mvc.perform(get(LIMITS_PATH).header("Authorization", "Bearer learner"))
                .andExpect(status().isForbidden());
        mvc.perform(put(CONFIG_PATH).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
        mvc.perform(
                        put(CONFIG_PATH)
                                .header("Authorization", "Bearer learner")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                .andExpect(status().isForbidden());
        assertEquals(before, databaseSnapshot());
    }

    private ResultActions updateConfig(Object templateId, Object mapping) throws Exception {
        return mvc.perform(
                put(CONFIG_PATH)
                        .header("Authorization", "Bearer administrator")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                Data.json(
                                        Data.map(
                                                "templateId",
                                                templateId,
                                                "fieldMapping",
                                                mapping))));
    }

    private Map<Store.Table, List<Map<String, Object>>> databaseSnapshot() {
        Map<Store.Table, List<Map<String, Object>>> snapshot = new EnumMap<>(Store.Table.class);
        for (Store.Table table : Store.Table.values()) {
            snapshot.put(
                    table,
                    fixture.store.all(table).stream().map(StoredRecordDO::toBoundary).toList());
        }
        return snapshot;
    }

    private void token(String rawToken, String role, String actorId) {
        fixture.store.create(
                Store.Table.AUTH_TOKEN,
                Data.map(
                        "role",
                        role,
                        "actorId",
                        actorId,
                        "expiresMs",
                        Long.MAX_VALUE,
                        "status",
                        "ACTIVE"),
                actorId,
                null,
                "ACTIVE",
                Data.hash(rawToken));
    }
}
