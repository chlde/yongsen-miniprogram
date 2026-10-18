package com.yongsen.training;

import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.crypto.encrypt.TextEncryptor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Clock;

class OnboardingControllerTest {
    private static final String PATH = "/api/v1/admin/onboarding";
    private static final String REQUEST =
            "{\"tutorialId\":\"admin-basics-v1\",\"status\":\"DISMISSED\"}";
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        BackendFixture fixture = new BackendFixture();
        for (String id : new String[] {"admin-a", "admin-b"}) {
            fixture.store.create(
                    Store.Table.ADMIN_USER,
                    Data.map(
                            "id", id, "username", id, "passwordHash", "unused", "status", "ACTIVE"),
                    null,
                    null,
                    "ACTIVE",
                    id);
            token(fixture.store, id, "ADMIN", id);
        }
        token(fixture.store, "learner", "LEARNER", "learner-identity");
        AuthService auth =
                new AuthService(
                        fixture.store,
                        mock(WxProvider.class),
                        mock(TextEncryptor.class),
                        new MockEnvironment(),
                        "unused");
        OnboardingService onboarding =
                new OnboardingService(fixture.store, fixture.transactions, Clock.systemUTC());
        mvc =
                MockMvcBuilders.standaloneSetup(new OnboardingController(auth, onboarding))
                        .setControllerAdvice(new ApiAdvice())
                        .build();
    }

    @Test
    void authenticatedAdministratorOnlyReadsAndWritesOwnPreference() throws Exception {
        mvc.perform(get(PATH).header("Authorization", "Bearer admin-a"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.tutorialId").value(OnboardingService.TUTORIAL_ID))
                .andExpect(jsonPath("$.data.status").value("NOT_STARTED"))
                .andExpect(jsonPath("$.data.updatedAt").value((Object) null));
        mvc.perform(
                        put(PATH)
                                .header("Authorization", "Bearer admin-a")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(REQUEST))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DISMISSED"))
                .andExpect(jsonPath("$.data.updatedAt").isString());
        mvc.perform(get(PATH).header("Authorization", "Bearer admin-b"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("NOT_STARTED"));
    }

    @Test
    void learnerAndUnauthenticatedRequestsCannotReadOrUpdateTutorialState() throws Exception {
        mvc.perform(get(PATH)).andExpect(status().isUnauthorized());
        mvc.perform(put(PATH).contentType(MediaType.APPLICATION_JSON).content(REQUEST))
                .andExpect(status().isUnauthorized());
        mvc.perform(get(PATH).header("Authorization", "Bearer learner"))
                .andExpect(status().isForbidden());
        mvc.perform(
                        put(PATH)
                                .header("Authorization", "Bearer learner")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(REQUEST))
                .andExpect(status().isForbidden());
    }

    @Test
    void invalidTutorialAndMalformedBodyReturnClientErrors() throws Exception {
        mvc.perform(
                        put(PATH)
                                .header("Authorization", "Bearer admin-a")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"tutorialId\":\"another-tutorial\",\"status\":\"COMPLETED\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        mvc.perform(
                        put(PATH)
                                .header("Authorization", "Bearer admin-a")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{broken"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        mvc.perform(get(PATH).header("Authorization", "Bearer admin-a"))
                .andExpect(jsonPath("$.data.status").value("NOT_STARTED"));
    }

    private void token(Store store, String rawToken, String role, String actorId) {
        store.create(
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
