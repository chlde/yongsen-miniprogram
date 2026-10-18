package com.yongsen.training;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Commits work together with business changes and dispatches only after the commit succeeds. */
@Service
public class BackgroundWorkDispatcher {
    private static final Logger LOGGER = LoggerFactory.getLogger(BackgroundWorkDispatcher.class);
    private final BackgroundWorkStore jobs;
    private final TransactionTemplate transactions;
    private final TransactionTemplate independent;
    private final BackgroundInvoker invoker;
    private final boolean enabled;
    private final ObjectProvider<MediaService> media;
    private final ObjectProvider<NotificationService> notifications;

    @Autowired
    public BackgroundWorkDispatcher(
            BackgroundWorkStore jobs,
            TransactionTemplate transactions,
            BackgroundInvoker invoker,
            Environment environment,
            ObjectProvider<MediaService> media,
            ObjectProvider<NotificationService> notifications) {
        this.jobs = jobs;
        this.transactions = transactions;
        this.independent = new TransactionTemplate(transactions.getTransactionManager());
        this.independent.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.invoker = invoker;
        String mode =
                environment.getProperty(
                        "training.background.mode",
                        environment.getProperty("TRAINING_BACKGROUND_MODE", "local"));
        if (!List.of("local", "scf", "manual").contains(mode)
                || ("manual".equals(mode) && !environment.matchesProfiles("local", "test"))) {
            throw new IllegalStateException("Invalid background execution mode");
        }
        if (environment.matchesProfiles("serverless") && "local".equals(mode)) {
            throw new IllegalStateException("Serverless cannot use polling background mode");
        }
        enabled = !"local".equals(mode);
        this.media = media;
        this.notifications = notifications;
    }

    public boolean enabled() {
        return enabled;
    }

    public void scheduleAfterCommit(String kind, String id) {
        if (!enabled) {
            return;
        }
        Data.require(
                List.of("MEDIA", "NOTIFICATIONS").contains(kind)
                        && id != null
                        && !id.isBlank()
                        && id.length() <= 128,
                "INVALID_WORK",
                "Invalid background work target");
        transactions.executeWithoutResult(
                status -> {
                    String jobId = jobs.enqueue(kind, id);
                    BatchDispatch callback =
                            TransactionSynchronizationManager.getSynchronizations().stream()
                                    .filter(item -> item instanceof BatchDispatch)
                                    .map(item -> (BatchDispatch) item)
                                    .filter(item -> item.owner() == this)
                                    .findFirst()
                                    .orElse(null);
                    if (callback == null) {
                        callback = new BatchDispatch();
                        TransactionSynchronizationManager.registerSynchronization(callback);
                    }
                    callback.ids.add(jobId);
                });
    }

    private final class BatchDispatch implements TransactionSynchronization {
        private final Set<String> ids = new LinkedHashSet<>();

        BackgroundWorkDispatcher owner() {
            return BackgroundWorkDispatcher.this;
        }

        @Override
        public void afterCommit() {
            for (String id : ids) {
                dispatch(id);
            }
        }
    }

    public boolean dispatch(String id) {
        boolean accepted;
        try {
            invoker.invoke(id);
            accepted = true;
        } catch (RuntimeException exception) {
            LOGGER.warn(
                    "Background dispatch deferred for {} ({})",
                    id,
                    exception.getClass().getSimpleName());
            accepted = false;
        }
        boolean outcome = accepted;
        independent.executeWithoutResult(status -> jobs.dispatchResult(id, outcome));
        return accepted;
    }

    public String run(String id) {
        Data.require(enabled, "BACKGROUND_DISABLED", "Event worker is disabled");
        BackgroundWorkStore.Work claimed = transactions.execute(status -> jobs.claim(id));
        if (!"RUNNING".equals(claimed.status())) {
            return "BUSY".equals(claimed.status())
                    ? "busy"
                    : "FAILED".equals(claimed.status()) ? "failed" : "done";
        }
        // The claim carries a fresh token only when this invocation acquired the lease.
        return executeClaimed(claimed);
    }

    private String executeClaimed(BackgroundWorkStore.Work claimed) {
        try {
            NotificationService.DispatchBatch batch =
                    new NotificationService.DispatchBatch("", false);
            if ("MEDIA".equals(claimed.kind())) {
                media.getObject().processOne(claimed.targetId());
            } else {
                batch =
                        notifications
                                .getObject()
                                .dispatchBatch(claimed.targetId(), claimed.cursor());
            }
            NotificationService.DispatchBatch outcome = batch;
            boolean more =
                    transactions.execute(
                            status -> jobs.finish(claimed, outcome.cursor(), outcome.more()));
            if (more && !dispatch(claimed.id())) {
                return "retry";
            }
            return "done";
        } catch (RuntimeException exception) {
            String code =
                    exception instanceof BusinessException business
                            ? business.code()
                            : exception.getClass().getSimpleName();
            LOGGER.warn("Background work deferred for {} ({})", claimed.id(), code);
            return transactions.execute(status -> jobs.fail(claimed, code));
        }
    }

    public void retry(String id) {
        transactions.executeWithoutResult(status -> jobs.retry(id));
        dispatch(id);
    }
}
