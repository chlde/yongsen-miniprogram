package com.yongsen.training;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.encrypt.TextEncryptor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class AuthService implements ApplicationRunner {
    private static final long TOKEN_TTL_MS = 8L * 60 * 60 * 1000;
    private static final long INVITE_TTL_MS = 7L * 24 * 60 * 60 * 1000;
    private static final int LOGIN_LIMIT = 20;
    private final Store store;
    private final WxProvider wx;
    private final TextEncryptor encryptor;
    private final Environment environment;
    private final BCryptPasswordEncoder passwords = new BCryptPasswordEncoder();
    private final ConcurrentHashMap<String, long[]> limits = new ConcurrentHashMap<>();
    private final String adminPassword;
    private BackgroundWorkDispatcher backgroundWork;

    @Autowired
    public void setBackgroundWork(BackgroundWorkDispatcher backgroundWork) {
        this.backgroundWork = backgroundWork;
    }

    public AuthService(
            Store store,
            WxProvider wx,
            TextEncryptor encryptor,
            Environment environment,
            @Value("${training.admin-password}") String adminPassword) {
        this.store = store;
        this.wx = wx;
        this.encryptor = encryptor;
        this.environment = environment;
        this.adminPassword = adminPassword;
    }

    public boolean local() {
        return environment.matchesProfiles("local", "test");
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!local() && (adminPassword.isBlank() || "LocalTrain123!".equals(adminPassword))) {
            throw new IllegalStateException("A non-default TRAINING_ADMIN_PASSWORD is required");
        }
        if (store.all(Store.Table.ADMIN_USER).stream()
                .map(StoredRecordDO::toBoundary)
                .toList()
                .isEmpty()) {
            createInitialRecord(
                    Store.Table.ADMIN_USER,
                    Data.map(
                            "username",
                            "admin",
                            "passwordHash",
                            passwords.encode(adminPassword),
                            "status",
                            "ACTIVE"),
                    null,
                    null,
                    "ACTIVE",
                    "admin");
        }
        if (store.maybe(Store.Table.APP_CONFIG, "notification") == null) {
            createInitialRecord(
                    Store.Table.APP_CONFIG,
                    Data.map(
                            "id",
                            "notification",
                            "templateId",
                            "mock".equals(wx.mode()) ? "mock-training-template" : "",
                            "fieldMapping",
                            Data.map("title", "thing1", "dueAt", "time2")),
                    null,
                    null,
                    "ACTIVE",
                    "notification");
        }
    }

    private void createInitialRecord(
            Store.Table table,
            Map<String, Object> body,
            String reference,
            String reference2,
            String status,
            String uniqueKey) {
        try {
            store.create(table, body, reference, reference2, status, uniqueKey);
        } catch (DuplicateKeyException exception) {
            // Another cold-starting instance may initialize the same shared database first.
            // Verify that record exists; do not hide other constraint or storage failures.
            if (store.findKey(table, uniqueKey).isEmpty()) {
                throw exception;
            }
        }
    }

    public void rate(String key) {
        long now = System.currentTimeMillis();
        long[] state =
                limits.compute(
                        key,
                        (ignored, old) ->
                                old == null || now - old[0] > 60000
                                        ? new long[] {now, 1}
                                        : new long[] {old[0], old[1] + 1});
        int limit = key.startsWith("wx:") ? 200 : LOGIN_LIMIT;
        if (state[1] > limit) {
            throw new BusinessException("RATE_LIMITED", "Too many attempts; try again later", 429);
        }
        if (limits.size() > 10000) {
            limits.entrySet().removeIf(entry -> now - entry.getValue()[0] > 60000);
        }
    }

    public Map<String, Object> login(Map<String, Object> input, String remote) {
        rate("admin:" + remote);
        List<Map<String, Object>> users =
                store.all(Store.Table.ADMIN_USER).stream().map(StoredRecordDO::toBoundary).toList();
        Map<String, Object> user =
                users.stream()
                        .filter(
                                row ->
                                        Data.str(row, "username")
                                                .equals(Data.str(input, "username")))
                        .findFirst()
                        .orElse(null);
        if (user == null
                || !"ACTIVE".equals(user.get("status"))
                || !passwords.matches(
                        Data.str(input, "password"), Data.str(user, "passwordHash"))) {
            throw new BusinessException("LOGIN_FAILED", "Invalid credentials", 401);
        }
        return token("ADMIN", Data.str(user, "id"), "", Data.map("user", Store.publicView(user)));
    }

    public Map<String, Object> wechatLogin(Map<String, Object> input, String remote) {
        rate("wx:" + remote);
        String openid = wx.login(Data.required(input, "code"));
        String identity = Data.hash(wx.appid() + ":" + openid);
        Map<String, Object> result =
                token("LEARNER", identity, encryptor.encrypt(openid), Map.of());
        result.putAll(meIdentity(identity));
        return result;
    }

    private Map<String, Object> token(
            String role, String actorId, String encryptedOpenid, Map<String, Object> extra) {
        String raw = Data.token();
        long expiry = System.currentTimeMillis() + TOKEN_TTL_MS;
        store.create(
                Store.Table.AUTH_TOKEN,
                Data.map(
                        "role",
                        role,
                        "actorId",
                        actorId,
                        "encryptedOpenid",
                        encryptedOpenid,
                        "expiresMs",
                        expiry,
                        "status",
                        "ACTIVE"),
                actorId,
                null,
                "ACTIVE",
                Data.hash(raw));
        Map<String, Object> result =
                Data.map(
                        "accessToken",
                        raw,
                        "expiresAt",
                        Instant.ofEpochMilli(expiry).toString(),
                        "role",
                        role);
        result.putAll(extra);
        return result;
    }

    public Map<String, Object> actor(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            throw new BusinessException("UNAUTHORIZED", "Login required", 401);
        }
        Map<String, Object> token;
        try {
            token =
                    store.byKey(Store.Table.AUTH_TOKEN, Data.hash(header.substring(7)))
                            .toBoundary();
        } catch (BusinessException exception) {
            throw new BusinessException("UNAUTHORIZED", "Invalid session", 401);
        }
        if (!"ACTIVE".equals(token.get("status"))
                || Data.num(token, "expiresMs", 0) < System.currentTimeMillis()) {
            throw new BusinessException("UNAUTHORIZED", "Session expired", 401);
        }
        return token;
    }

    public Map<String, Object> admin(HttpServletRequest request) {
        Map<String, Object> actor = actor(request);
        if (!"ADMIN".equals(actor.get("role"))) {
            throw new BusinessException("FORBIDDEN", "Administrator required", 403);
        }
        Data.require(
                "ACTIVE"
                        .equals(
                                store.get(Store.Table.ADMIN_USER, Data.str(actor, "actorId"))
                                        .toBoundary()
                                        .get("status")),
                "ACCOUNT_DISABLED",
                "Account disabled");
        return actor;
    }

    public Map<String, Object> binding(Map<String, Object> actor, boolean required) {
        if (!"LEARNER".equals(actor.get("role"))) {
            throw new BusinessException("FORBIDDEN", "Learner identity required", 403);
        }
        List<Map<String, Object>> bindings =
                store.refs(Store.Table.WX_IDENTITY_BINDING, Data.str(actor, "actorId")).stream()
                        .map(StoredRecordDO::toBoundary)
                        .toList();
        Map<String, Object> binding =
                bindings.stream()
                        .filter(row -> "ACTIVE".equals(row.get("status")))
                        .findFirst()
                        .orElse(null);
        if (binding != null) {
            Data.require(
                    "ACTIVE"
                            .equals(
                                    store.get(Store.Table.PERSON, Data.str(binding, "personId"))
                                            .toBoundary()
                                            .get("status")),
                    "PERSON_INACTIVE",
                    "Person is inactive");
        }
        if (required && binding == null) {
            throw new BusinessException("BINDING_REQUIRED", "Approved binding required", 403);
        }
        return binding;
    }

    public Map<String, Object> me(Map<String, Object> actor) {
        if ("ADMIN".equals(actor.get("role"))) {
            return Data.map("role", "ADMIN", "providerMode", wx.mode());
        }
        Map<String, Object> result = meIdentity(Data.str(actor, "actorId"));
        result.put("role", "LEARNER");
        result.put("providerMode", wx.mode());
        return result;
    }

    private Map<String, Object> meIdentity(String identity) {
        List<Map<String, Object>> bindings =
                store.refs(Store.Table.WX_IDENTITY_BINDING, identity).stream()
                        .map(StoredRecordDO::toBoundary)
                        .toList();
        Map<String, Object> current =
                bindings.stream()
                        .filter(
                                row ->
                                        "ACTIVE".equals(row.get("status"))
                                                || "PENDING".equals(row.get("status")))
                        .findFirst()
                        .orElse(null);
        return Data.map(
                "bindingStatus",
                current == null ? "UNBOUND" : current.get("status"),
                "person",
                current == null
                        ? null
                        : Store.publicView(
                                store.get(Store.Table.PERSON, Data.str(current, "personId"))
                                        .toBoundary()));
    }

    public String openid(Map<String, Object> binding) {
        return encryptor.decrypt(Data.str(binding, "encryptedOpenid"));
    }

    @Transactional
    public Map<String, Object> invitation(String personId, String actorId) {
        Map<String, Object> person = store.lock(Store.Table.PERSON, personId).toBoundary();
        String raw = Data.token();
        long expires = System.currentTimeMillis() + INVITE_TTL_MS;
        person.put("inviteHash", Data.hash(raw));
        person.put("inviteExpiresMs", expires);
        person.put("inviteConsumed", false);
        store.save(Store.Table.PERSON, person, Data.str(person, "status"));
        store.audit(actorId, "INVITE_CREATED", personId, Map.of());
        return Data.map(
                "inviteCode",
                raw,
                "expiresAt",
                Instant.ofEpochMilli(expires).toString(),
                "personId",
                personId);
    }

    @Transactional
    public Map<String, Object> requestBinding(Map<String, Object> actor, String inviteCode) {
        if (!"LEARNER".equals(actor.get("role"))) {
            throw new BusinessException("FORBIDDEN", "Learner identity required", 403);
        }
        rate("invite:" + actor.get("actorId"));
        String digest = Data.hash(inviteCode);
        Map<String, Object> match =
                store.all(Store.Table.PERSON).stream()
                        .map(StoredRecordDO::toBoundary)
                        .toList()
                        .stream()
                        .filter(person -> digest.equals(person.get("inviteHash")))
                        .findFirst()
                        .orElse(null);
        Data.require(match != null, "INVITE_INVALID", "Invalid invitation");
        Map<String, Object> person =
                store.lock(Store.Table.PERSON, Data.str(match, "id")).toBoundary();
        Data.require(
                !Data.bool(person, "inviteConsumed")
                        && Data.num(person, "inviteExpiresMs", 0) > System.currentTimeMillis()
                        && "ACTIVE".equals(person.get("status")),
                "INVITE_INVALID",
                "Invalid or expired invitation");
        boolean occupied =
                store.all(Store.Table.WX_IDENTITY_BINDING).stream()
                        .map(StoredRecordDO::toBoundary)
                        .toList()
                        .stream()
                        .anyMatch(
                                row ->
                                        ("ACTIVE".equals(row.get("status"))
                                                        || "PENDING".equals(row.get("status")))
                                                && (person.get("id").equals(row.get("personId"))
                                                        || actor.get("actorId")
                                                                .equals(row.get("identityId"))));
        Data.require(!occupied, "BINDING_EXISTS", "Person or WeChat already has a binding");
        person.put("inviteConsumed", true);
        store.save(Store.Table.PERSON, person, "ACTIVE");
        return store.create(
                Store.Table.WX_IDENTITY_BINDING,
                Data.map(
                        "personId",
                        person.get("id"),
                        "personName",
                        person.get("name"),
                        "identityId",
                        actor.get("actorId"),
                        "encryptedOpenid",
                        actor.get("encryptedOpenid"),
                        "status",
                        "PENDING",
                        "inviteHash",
                        digest),
                Data.str(actor, "actorId"),
                Data.str(person, "id"),
                "PENDING",
                "person:" + person.get("id"));
    }

    @Transactional
    public Map<String, Object> decision(String id, Map<String, Object> input, String adminId) {
        Map<String, Object> row = store.lock(Store.Table.WX_IDENTITY_BINDING, id).toBoundary();
        Data.require(
                "PENDING".equals(row.get("status")), "BINDING_STATE", "Binding is not pending");
        String decision = Data.required(input, "decision");
        Data.require(
                List.of("APPROVE", "REJECT").contains(decision),
                "INVALID_INPUT",
                "Invalid decision");
        Map<String, Object> person =
                store.lock(Store.Table.PERSON, Data.str(row, "personId")).toBoundary();
        Data.require(
                row.get("inviteHash").equals(person.get("inviteHash")),
                "INVITE_INVALID",
                "Invitation replaced");
        row.put("status", "APPROVE".equals(decision) ? "ACTIVE" : "REJECTED");
        row.put("checkedAt", Data.now());
        row.put("checkedBy", adminId);
        if ("REJECTED".equals(row.get("status"))) {
            row.put("_key", null);
        }
        store.save(Store.Table.WX_IDENTITY_BINDING, row, Data.str(row, "status"));
        store.audit(
                adminId,
                "BINDING_" + decision,
                id,
                Data.map("reason", Data.required(input, "reason")));
        if ("ACTIVE".equals(row.get("status")) && backgroundWork != null) {
            backgroundWork.scheduleAfterCommit("NOTIFICATIONS", Data.str(row, "personId"));
        }
        return publicBinding(row);
    }

    public Map<String, Object> publicBinding(Map<String, Object> row) {
        Map<String, Object> result = Store.publicView(row);
        result.remove("encryptedOpenid");
        result.remove("identityId");
        return result;
    }

    @Transactional
    public void revoke(String id, String adminId, String reason) {
        Map<String, Object> row = store.lock(Store.Table.WX_IDENTITY_BINDING, id).toBoundary();
        row.put("status", "REVOKED");
        row.put("_key", null);
        store.save(Store.Table.WX_IDENTITY_BINDING, row, "REVOKED");
        for (Map<String, Object> token :
                store.refs(Store.Table.AUTH_TOKEN, Data.str(row, "identityId")).stream()
                        .map(StoredRecordDO::toBoundary)
                        .toList()) {
            token.put("status", "REVOKED");
            store.save(Store.Table.AUTH_TOKEN, token, "REVOKED");
        }
        store.audit(adminId, "BINDING_REVOKED", id, Data.map("reason", reason));
    }
}
