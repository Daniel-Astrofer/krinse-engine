package com.kerosene.kfe.paymentexecution.adapters.in.scheduling;

import com.kerosene.kfe.paymentexecution.application.port.in.ProcessExecutionUseCase;
import com.kerosene.kfe.paymentexecution.application.port.out.ExecutionClaimPort;
import com.kerosene.kfe.paymentexecution.domain.model.ExecutionClaim;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Claims due durable commands and processes them without holding a transaction across provider I/O. */
@Component
@ConditionalOnProperty(name = "kfe.execution.enabled", havingValue = "true", matchIfMissing = true)
public class KfeExecutionOutboxWorker {

    /** Operational logger for claims and failures that need lease recovery or reconciliation. */
    private static final Logger log = LoggerFactory.getLogger(KfeExecutionOutboxWorker.class);
    /** Unique lease owner identity so concurrent worker instances do not share claims. */
    private final String workerId = "kfe-execution-worker-" + UUID.randomUUID();

    /** Atomically claims due commands and renews current leases. */
    private final ExecutionClaimPort outboxService;
    /** Executes each claimed command through the application boundary. */
    private final ProcessExecutionUseCase processor;

    /** Supplies the lease port and execution workflow for this worker instance. */
    public KfeExecutionOutboxWorker(
            ExecutionClaimPort outboxService,
            ProcessExecutionUseCase processor) {
        this.outboxService = Objects.requireNonNull(outboxService);
        this.processor = Objects.requireNonNull(processor);
    }

    /** Claims one due batch and processes each item independently, leaving recovery to leases on failure. */
    @Scheduled(
            fixedDelayString = "${kfe.execution.outbox.fixed-delay-ms:5000}",
            initialDelayString = "${kfe.execution.outbox.initial-delay-ms:10000}")
    public void drain() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Execution worker must start outside an existing transaction.");
        }
        List<ExecutionClaim> claimed = outboxService.claimDue(workerId);
        if (claimed.isEmpty()) {
            return;
        }
        log.info("[KFE Outbox] claimed {} item(s) workerId={}", claimed.size(), workerId);
        for (ExecutionClaim claim : claimed) {
            try {
                processor.process(claim);
            } catch (RuntimeException exception) {
                log.warn("[KFE Outbox] Processing failed for {}; lease recovery or reconciliation may be required.",
                        claim.outboxId());
            }
        }
    }
}
