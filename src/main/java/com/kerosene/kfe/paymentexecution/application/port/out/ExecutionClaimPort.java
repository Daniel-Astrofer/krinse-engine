package com.kerosene.kfe.paymentexecution.application.port.out;

import com.kerosene.kfe.paymentexecution.domain.model.ExecutionClaim;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Short, atomic claim operations. The caller must not hold a transaction across provider I/O. */
public interface ExecutionClaimPort {
    /** Atomically leases currently due commands to the named worker. */
    /** @param workerId stable worker instance identifier @return claims carrying current lease tokens */
    List<ExecutionClaim> claimDue(String workerId);
    /** Attempts an immediate lease for one command after the business transaction commits. */
    /** @param outboxId durable command identifier @param workerId requesting worker @return current claim, or empty when another worker owns it or it is not eligible */
    Optional<ExecutionClaim> claimImmediate(UUID outboxId, String workerId);
    /** Renews only current unexpired ownership, never resurrecting a stale lease. */
    boolean heartbeat(ExecutionClaim claim);
}
