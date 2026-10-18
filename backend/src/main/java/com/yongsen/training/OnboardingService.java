package com.yongsen.training;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.Map;

/** Stores only the current administrator's tutorial preference, never training progress. */
@Service
public class OnboardingService {
    public static final String TUTORIAL_ID = "admin-basics-v1";
    private static final String CONFIG_NAMESPACE = "admin-onboarding:";
    private static final String FIELD_TUTORIAL_ID = "tutorialId";
    private static final String FIELD_STATUS = "status";
    private static final String FIELD_UPDATED_AT = "updatedAt";
    private static final String ACTIVE = "ACTIVE";
    private static final String INVALID_INPUT = "INVALID_INPUT";
    private final Store store;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public enum Status {
        NOT_STARTED,
        DISMISSED,
        COMPLETED
    }

    public record UpdateRequest(String tutorialId, String status) {}

    public record Progress(String tutorialId, Status status, String updatedAt) {}

    public OnboardingService(Store store, TransactionTemplate transactions, Clock clock) {
        this.store = store;
        this.transactions = transactions;
        this.clock = clock;
    }

    /** An absent preference is a read-only NOT_STARTED result; persistence errors propagate. */
    public Progress getProgress(String adminId) {
        return progress(store.maybe(Store.Table.APP_CONFIG, configId(adminId)), adminId);
    }

    /** Invalid requests return INVALID_INPUT; completed tutorials cannot be dismissed again. */
    public Progress updateProgress(String adminId, UpdateRequest input) {
        Status requested = validate(input);
        return transactions.execute(
                transaction -> {
                    StoredRecordDO administrator = store.lock(Store.Table.ADMIN_USER, adminId);
                    DomainRecords.AdminUserDO account =
                            (DomainRecords.AdminUserDO) administrator.domainData();
                    if (!ACTIVE.equals(account.status())) {
                        throw new BusinessException(
                                "ACCOUNT_DISABLED",
                                "Account disabled",
                                HttpStatus.FORBIDDEN.value());
                    }
                    StoredRecordDO existing =
                            store.maybe(Store.Table.APP_CONFIG, configId(adminId));
                    Progress current = progress(existing, adminId);
                    if (current.status() == Status.COMPLETED || current.status() == requested) {
                        return current;
                    }
                    String updatedAt = clock.instant().truncatedTo(ChronoUnit.MILLIS).toString();
                    Map<String, Object> metadata =
                            existing == null ? Data.map() : existing.toBoundary();
                    metadata.putAll(
                            Data.map(
                                    FIELD_TUTORIAL_ID,
                                    TUTORIAL_ID,
                                    FIELD_STATUS,
                                    requested.name(),
                                    FIELD_UPDATED_AT,
                                    updatedAt));
                    if (existing == null) {
                        metadata.put("id", configId(adminId));
                        store.create(
                                Store.Table.APP_CONFIG,
                                metadata,
                                adminId,
                                TUTORIAL_ID,
                                requested.name(),
                                configKey(adminId));
                    } else {
                        store.save(Store.Table.APP_CONFIG, metadata, requested.name());
                    }
                    return new Progress(TUTORIAL_ID, requested, updatedAt);
                });
    }

    private Status validate(UpdateRequest input) {
        if (input == null || !TUTORIAL_ID.equals(input.tutorialId())) {
            throw new BusinessException(
                    INVALID_INPUT, "Unsupported tutorialId", HttpStatus.BAD_REQUEST.value());
        }
        if (Status.DISMISSED.name().equals(input.status())) {
            return Status.DISMISSED;
        }
        if (Status.COMPLETED.name().equals(input.status())) {
            return Status.COMPLETED;
        }
        throw new BusinessException(
                INVALID_INPUT,
                "Status must be DISMISSED or COMPLETED",
                HttpStatus.BAD_REQUEST.value());
    }

    private Progress progress(StoredRecordDO stored, String adminId) {
        if (stored == null) {
            return new Progress(TUTORIAL_ID, Status.NOT_STARTED, null);
        }
        Map<String, Object> metadata = Data.parse(stored.snapshotJson());
        String state = Data.str(metadata, FIELD_STATUS);
        String updatedAt = Data.str(metadata, FIELD_UPDATED_AT);
        if (!adminId.equals(stored.referenceId())
                || !TUTORIAL_ID.equals(stored.secondReferenceId())
                || !TUTORIAL_ID.equals(metadata.get(FIELD_TUTORIAL_ID))
                || updatedAt.isBlank()
                || (!Status.DISMISSED.name().equals(state)
                        && !Status.COMPLETED.name().equals(state))) {
            throw new IllegalStateException("Stored onboarding preference is invalid");
        }
        return new Progress(TUTORIAL_ID, Status.valueOf(state), updatedAt);
    }

    private String configId(String adminId) {
        return Data.hash(configKey(adminId));
    }

    private String configKey(String adminId) {
        return CONFIG_NAMESPACE + adminId + ":" + TUTORIAL_ID;
    }
}
