package com.yongsen.training;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.HttpServletRequest;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRange;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Map;

/** Full local controller/event flow. COS transport and SCF dispatch are byte-preserving fakes. */
@SpringBootTest(
        properties = {
            "training.background.mode=manual",
            "training.storage.mode=local",
            "training.wechat-mode=mock",
            "training.admin-password=LocalTrain123!",
            "TRAINING_WORKER_SECRET=local-integration-worker-secret-1234567890",
            "spring.datasource.url=jdbc:h2:mem:serverless-media-flow;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
        })
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({
    ServerlessMediaFlowTest.ObjectsConfiguration.class,
    ServerlessMediaFlowTest.ObjectBoundary.class
})
class ServerlessMediaFlowTest {
    private static final String WORKER_AUTH = "Bearer local-integration-worker-secret-1234567890";
    private static final Path scratch = temporaryDirectory();
    @TempDir Path restartedScratch;
    @Autowired MockMvc api;
    @Autowired FakeMediaObjectStorage objects;
    @Autowired MediaService media;
    @Autowired Store store;
    @Autowired TransactionTemplate transactions;
    @Autowired BackgroundWorkDispatcher dispatcher;
    @Autowired BackgroundWorkStore jobs;
    @Autowired AuthService auth;
    @Autowired Environment environment;

    private static Path temporaryDirectory() {
        try {
            return Files.createTempDirectory("serverless-media-flow-");
        } catch (IOException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    @AfterAll
    static void cleanup() throws IOException {
        try (var paths = Files.walk(scratch)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("training.data-dir", () -> scratch.toString());
    }

    @TestConfiguration
    static class ObjectsConfiguration {
        @Bean
        FakeMediaObjectStorage fakeMediaObjectStorage() {
            return new FakeMediaObjectStorage();
        }
    }

    /** Test-only HTTP object transport; the real signed URL contract is tested with the COS SDK. */
    @RestController
    static class ObjectBoundary {
        private final FakeMediaObjectStorage objects;

        ObjectBoundary(FakeMediaObjectStorage objects) {
            this.objects = objects;
        }

        @PutMapping("/uploads/**")
        ResponseEntity<Void> upload(
                @RequestParam String signature,
                @RequestBody byte[] body,
                HttpServletRequest request) {
            if (!"test".equals(signature)) return ResponseEntity.status(403).build();
            objects.objects.put(request.getRequestURI().substring(1), body.clone());
            return ResponseEntity.ok().build();
        }

        @GetMapping("/materials/**")
        ResponseEntity<byte[]> download(
                @RequestParam String signature, HttpServletRequest request) {
            String key = request.getRequestURI().substring(1);
            if (!"read".equals(signature)
                    || !key.equals(objects.lastDownloadKey)
                    || objects.lastDownloadExpiry.isBefore(java.time.Instant.now())) {
                return ResponseEntity.status(403).build();
            }
            byte[] bytes = objects.objects.get(key);
            String range = request.getHeader("Range");
            if (range == null) return ResponseEntity.ok(bytes);
            HttpRange parsed = HttpRange.parseRanges(range).get(0);
            int start = (int) parsed.getRangeStart(bytes.length);
            int end = (int) parsed.getRangeEnd(bytes.length);
            return ResponseEntity.status(206)
                    .header(
                            HttpHeaders.CONTENT_RANGE,
                            "bytes " + start + "-" + end + "/" + bytes.length)
                    .body(Arrays.copyOfRange(bytes, start, end + 1));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"MP4", "PDF"})
    void authorizedUploadDispatchAndFreshWorkerSurviveScratchLoss(String type) throws Exception {
        int initialCopies = objects.copies.get();
        String contentType = "MP4".equals(type) ? "video/mp4" : "application/pdf";
        byte[] bytes = resource("MP4".equals(type) ? "training.mp4" : "training.pdf");
        String request = Data.json(Data.map("title", "云端流程模拟", "type", type, "size", bytes.length));
        api.perform(
                        post("/api/v1/admin/materials/uploads")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(request))
                .andExpect(status().isUnauthorized());
        Map<String, Object> login =
                data(
                        api.perform(
                                        post("/api/v1/admin/auth/login")
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .content(
                                                        "{\"username\":\"admin\",\"password\":\"LocalTrain123!\"}"))
                                .andExpect(status().isOk())
                                .andReturn());
        String authorization = "Bearer " + Data.str(login, "accessToken");
        Map<String, Object> created =
                data(
                        api.perform(
                                        post("/api/v1/admin/materials/uploads")
                                                .header("Authorization", authorization)
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .content(request))
                                .andExpect(status().isOk())
                                .andReturn());
        String id = Data.str(created, "materialId");
        URI uploadUrl = URI.create(Data.str(created, "uploadUrl"));
        api.perform(put(uploadUrl).contentType(contentType).content(bytes))
                .andExpect(status().isOk());
        api.perform(
                        post("/api/v1/admin/materials/" + id + "/upload-complete")
                                .header("Authorization", authorization))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.status").value("PROCESSING"));
        assertThat(objects.copies).hasValue(initialCopies);
        String jobId = Data.hash("MEDIA:" + id);
        assertThat(dispatcher.enabled()).isTrue();
        assertThat(jobs.get(jobId, false).status()).isEqualTo("DISPATCHED");
        media.processPending();
        assertThat(media.get(id).get("status")).isEqualTo("PROCESSING");
        api.perform(
                        post("/internal/background-work/" + jobId + "/run")
                                .header("Authorization", authorization))
                .andExpect(status().isForbidden());
        api.perform(
                        post("/api/v1/admin/materials/" + id + "/media-ticket")
                                .header("Authorization", authorization)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("MATERIAL_NOT_READY"));

        removeScratchFiles();
        MediaService freshWorker =
                new MediaService(
                        store,
                        transactions,
                        restartedScratch.toString(),
                        "https://api.example",
                        "ffprobe",
                        "pdftoppm",
                        "pdfinfo",
                        524288000);
        freshWorker.setObjectStorage(objects);
        DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
        beans.registerSingleton("media", freshWorker);
        beans.registerSingleton("notifications", mock(NotificationService.class));
        BackgroundWorkDispatcher restartedDispatcher =
                new BackgroundWorkDispatcher(
                        jobs,
                        transactions,
                        ignored -> {},
                        environment,
                        beans.getBeanProvider(MediaService.class),
                        beans.getBeanProvider(NotificationService.class));
        freshWorker.setDispatcher(restartedDispatcher);
        MockMvc restartedApi =
                MockMvcBuilders.standaloneSetup(
                                new BackgroundWorkController(
                                        restartedDispatcher, jobs, auth, store, environment),
                                new MediaController(auth, freshWorker))
                        .setControllerAdvice(new ApiAdvice())
                        .build();
        restartedApi
                .perform(
                        post("/internal/background-work/" + jobId + "/run")
                                .header("Authorization", WORKER_AUTH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("done"));
        assertThat(jobs.get(jobId, false).status()).isEqualTo("DONE");
        Map<String, Object> material = freshWorker.get(id);
        assertThat(material.get("status")).isEqualTo("READY");
        assertThat(material.get("sha256"))
                .isEqualTo(
                        HexFormat.of()
                                .formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
        // Once the worker freezes the confirmed version, this old credential changes staging only.
        byte[] overwritten = new byte[bytes.length];
        api.perform(put(uploadUrl).contentType(contentType).content(overwritten))
                .andExpect(status().isOk());
        assertThat(objects.objects.get(objects.lastUploadKey)).isEqualTo(overwritten);
        restartedApi
                .perform(
                        post("/internal/background-work/" + jobId + "/run")
                                .header("Authorization", WORKER_AUTH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("done"));
        restartedApi
                .perform(
                        post("/api/v1/admin/materials/" + id + "/media-ticket")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}"))
                .andExpect(status().isUnauthorized());
        Map<String, Object> ticket =
                data(
                        restartedApi
                                .perform(
                                        post("/api/v1/admin/materials/" + id + "/media-ticket")
                                                .header("Authorization", authorization)
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .content("{}"))
                                .andExpect(status().isOk())
                                .andReturn());
        MvcResult range =
                api.perform(get(URI.create(Data.str(ticket, "url"))).header("Range", "bytes=0-7"))
                        .andExpect(status().isPartialContent())
                        .andReturn();
        byte[] expected = "MP4".equals(type) ? bytes : objects.objects.get(objects.lastDownloadKey);
        assertThat(range.getResponse().getContentAsByteArray())
                .isEqualTo(Arrays.copyOf(expected, 8));
        try (var files = Files.list(restartedScratch.resolve("files"))) {
            assertThat(files.toList()).isEmpty();
        }
    }

    private void removeScratchFiles() throws IOException {
        try (var paths = Files.walk(scratch.resolve("files"))) {
            for (Path file : paths.filter(Files::isRegularFile).toList()) Files.delete(file);
        }
    }

    private static Map<String, Object> data(MvcResult result) throws Exception {
        return Data.obj(Data.parse(result.getResponse().getContentAsString()).get("data"));
    }

    private static byte[] resource(String name) throws IOException {
        try (var stream = new ClassPathResource("media/" + name).getInputStream()) {
            return stream.readAllBytes();
        }
    }
}
