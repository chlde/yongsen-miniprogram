package com.yongsen.training;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;

@RestController
public class BackgroundWorkController {
    private final BackgroundWorkDispatcher dispatcher;
    private final BackgroundWorkStore jobs;
    private final AuthService auth;
    private final Store store;
    private final byte[] secret;

    public BackgroundWorkController(
            BackgroundWorkDispatcher dispatcher,
            BackgroundWorkStore jobs,
            AuthService auth,
            Store store,
            Environment environment) {
        this.dispatcher = dispatcher;
        this.jobs = jobs;
        this.auth = auth;
        this.store = store;
        String configured = environment.getProperty("TRAINING_WORKER_SECRET", "");
        if (dispatcher.enabled() && configured.length() < 32) {
            throw new IllegalStateException(
                    "Event worker requires TRAINING_WORKER_SECRET of at least 32 characters");
        }
        secret = ("Bearer " + configured).getBytes(StandardCharsets.UTF_8);
    }

    @PostMapping("/internal/background-work/{id}/run")
    public Object run(@PathVariable String id, HttpServletRequest request) {
        String authorization = request.getHeader("Authorization");
        if (!dispatcher.enabled()
                || authorization == null
                || !MessageDigest.isEqual(secret, authorization.getBytes(StandardCharsets.UTF_8))) {
            throw new BusinessException("FORBIDDEN", "Worker authorization required", 403);
        }
        return Data.map("status", dispatcher.run(id));
    }

    @GetMapping("/api/v1/admin/background-work")
    public Object list(
            @RequestParam(defaultValue = "100") String limit,
            @RequestParam(defaultValue = "0") String offset,
            HttpServletRequest request) {
        auth.admin(request);
        int pageSize = pageNumber(limit, 1, 100);
        int pageOffset = pageNumber(offset, 0, 100000);
        List<BackgroundWorkStore.Work> rows = jobs.list(pageSize + 1, pageOffset);
        return ApiAdvice.ok(
                Data.map(
                        "items",
                        rows.stream()
                                .limit(pageSize)
                                .map(
                                        row ->
                                                Data.map(
                                                        "id",
                                                        row.id(),
                                                        "kind",
                                                        row.kind(),
                                                        "targetId",
                                                        row.targetId(),
                                                        "status",
                                                        row.status(),
                                                        "attempts",
                                                        row.attempts(),
                                                        "errorCode",
                                                        row.errorCode(),
                                                        "updatedMs",
                                                        row.updatedMs(),
                                                        "leaseUntilMs",
                                                        row.leaseUntil()))
                                .toList(),
                        "limit",
                        pageSize,
                        "offset",
                        pageOffset,
                        "hasMore",
                        rows.size() > pageSize));
    }

    private int pageNumber(String value, int minimum, int maximum) {
        int number;
        try {
            number = Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            throw new BusinessException("INVALID_PAGINATION", "Pagination must use integers", 400);
        }
        if (number < minimum || number > maximum) {
            throw new BusinessException(
                    "INVALID_PAGINATION", "Pagination is outside the allowed range", 400);
        }
        return number;
    }

    @PostMapping("/api/v1/admin/background-work/{id}/retry")
    public Object retry(@PathVariable String id, HttpServletRequest request) {
        Map<String, Object> actor = auth.admin(request);
        dispatcher.retry(id);
        store.audit(Data.str(actor, "actorId"), "BACKGROUND_WORK_RETRY", id, Map.of());
        return ApiAdvice.ok(Data.map("queued", true));
    }
}
