package com.kerosene.kfe.paymentexecution.adapters.out.persistence;

import com.kerosene.kfe.paymentexecution.application.port.out.ExecutionClaimPort;
import com.kerosene.kfe.paymentexecution.domain.model.ExecutionClaim;
import com.kerosene.kfe.adapters.out.persistence.repository.paymentexecution.KfeExecutionOutboxRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Claims are committed before RPC; candidates are bounded in SQL and acquired by compare-and-set. */
@Component
public class JpaExecutionClaimAdapter implements ExecutionClaimPort {
    private static final List<String> DUE_STATUSES = List.of("PENDING", "FAILED_RETRYABLE");
    private static final List<String> RECOVERABLE_OPERATIONS = List.of("ONCHAIN_OUTBOUND", "LIGHTNING_OUTBOUND");
    private final KfeExecutionOutboxRepository repository;
    private final Duration leaseDuration;

    public JpaExecutionClaimAdapter(KfeExecutionOutboxRepository repository,
            @Value("${kfe.execution.outbox.lease-seconds:600}") long leaseSeconds) {
        this.repository = Objects.requireNonNull(repository);
        if (leaseSeconds < 30L || leaseSeconds > 3600L) {
            throw new IllegalArgumentException("kfe.execution.outbox.lease-seconds must be between 30 and 3600.");
        }
        this.leaseDuration = Duration.ofSeconds(leaseSeconds);
    }

    @Override @Transactional
    public List<ExecutionClaim> claimDue(String workerId) {
        String worker = normalizeWorkerId(workerId);
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        return repository.findTop100ClaimCandidates(DUE_STATUSES, RECOVERABLE_OPERATIONS, now, PageRequest.of(0, 100))
                .stream().limit(100).map(candidate -> {
                    UUID token = UUID.randomUUID();
                    int updated = repository.claimDue(candidate.getId(), DUE_STATUSES, RECOVERABLE_OPERATIONS,
                            now, worker, token, now.plus(leaseDuration));
                    return updated == 1 ? Optional.of(new ExecutionClaim(candidate.getId(), token))
                            : Optional.<ExecutionClaim>empty();
                }).flatMap(Optional::stream).toList();
    }

    @Override @Transactional
    public Optional<ExecutionClaim> claimImmediate(UUID outboxId, String workerId) {
        if (outboxId == null) { return Optional.empty(); }
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        UUID token = UUID.randomUUID();
        int updated = repository.claimImmediate(outboxId, now, normalizeWorkerId(workerId), token, now.plus(leaseDuration));
        return updated == 1 ? Optional.of(new ExecutionClaim(outboxId, token)) : Optional.empty();
    }

    @Override @Transactional
    public boolean heartbeat(ExecutionClaim claim) {
        if (claim == null) { return false; }
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        return repository.heartbeat(claim.outboxId(), claim.claimToken(), now, now.plus(leaseDuration)) == 1;
    }

    private String normalizeWorkerId(String workerId) {
        String value = workerId == null ? "" : workerId.trim();
        if (value.isBlank()) { return "kfe-execution-worker"; }
        String lower = value.toLowerCase(Locale.ROOT);
        return lower.substring(0, Math.min(128, lower.length()));
    }
}
