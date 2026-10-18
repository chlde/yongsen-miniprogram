package com.yongsen.training;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/admin/onboarding")
public class OnboardingController {
    private final AuthService auth;
    private final OnboardingService onboarding;

    public OnboardingController(AuthService auth, OnboardingService onboarding) {
        this.auth = auth;
        this.onboarding = onboarding;
    }

    @GetMapping
    public Map<String, Object> get(HttpServletRequest request) {
        String adminId = Data.str(auth.admin(request), "actorId");
        return ApiAdvice.ok(onboarding.getProgress(adminId));
    }

    @PutMapping
    public Map<String, Object> update(
            @RequestBody OnboardingService.UpdateRequest input, HttpServletRequest request) {
        String adminId = Data.str(auth.admin(request), "actorId");
        return ApiAdvice.ok(onboarding.updateProgress(adminId, input));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> invalidBody(
            HttpMessageNotReadableException exception) {
        return ResponseEntity.badRequest()
                .body(
                        Data.map(
                                "code",
                                "INVALID_INPUT",
                                "message",
                                "A valid tutorialId and status are required",
                                "requestId",
                                Data.id(),
                                "details",
                                Map.of()));
    }
}
