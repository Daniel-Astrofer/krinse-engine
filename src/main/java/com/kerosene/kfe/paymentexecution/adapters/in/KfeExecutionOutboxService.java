package com.kerosene.kfe.paymentexecution.adapters.in;

import com.kerosene.kfe.paymentexecution.application.port.out.ExecutionClaimPort;
import com.kerosene.kfe.paymentexecution.adapters.out.persistence.JpaExecutionClaimAdapter;
import com.kerosene.kfe.adapters.out.persistence.repository.paymentexecution.KfeExecutionOutboxRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Compatibility facade for the legacy scheduler/dispatcher; claim persistence belongs to the port. */
@Service
public class KfeExecutionOutboxService {
    /** Port owning atomic outbox lease acquisition, renewal and fencing. */
    private final ExecutionClaimPort claims;

    /** Supplies the application claim port to this legacy scheduler facade. */
    @Autowired
    public KfeExecutionOutboxService(ExecutionClaimPort claims) {
        this.claims = Objects.requireNonNull(claims);
    }

    /** Builds the port-backed adapter for older package-local construction sites. */
    KfeExecutionOutboxService(KfeExecutionOutboxRepository repository) {
        this(new JpaExecutionClaimAdapter(repository, 600L));
    }

    /** Legacy representation of an outbox lease, retained for existing scheduler callers.
     * @param outboxId durable outbox command identifier
     * @param claimToken unforgeable token fencing writes to the current lease owner
     */
    public record ExecutionClaim(UUID outboxId, UUID claimToken) { }

    /** Claims every currently due command and converts leases to the legacy DTO. */
    public List<ExecutionClaim> claimDue(String workerId) {
        return claims.claimDue(workerId).stream().map(KfeExecutionOutboxService::legacy).toList();
    }

    /** Attempts to claim one eligible command immediately, returning empty if it is unavailable. */
    public Optional<ExecutionClaim> claimImmediate(UUID outboxId, String workerId) {
        return claims.claimImmediate(outboxId, workerId).map(KfeExecutionOutboxService::legacy);
    }

    /** Renews only a structurally valid current lease; malformed claims return false. */
    public boolean heartbeat(ExecutionClaim claim) {
        if (claim == null || claim.outboxId() == null || claim.claimToken() == null) { return false; }
        return claims.heartbeat(new com.kerosene.kfe.paymentexecution.domain.model.ExecutionClaim(claim.outboxId(), claim.claimToken()));
    }

    /** Maps the domain lease to the DTO expected by the compatibility boundary. */
    private static ExecutionClaim legacy(com.kerosene.kfe.paymentexecution.domain.model.ExecutionClaim claim) {
        return new ExecutionClaim(claim.outboxId(), claim.claimToken());
    }
}
