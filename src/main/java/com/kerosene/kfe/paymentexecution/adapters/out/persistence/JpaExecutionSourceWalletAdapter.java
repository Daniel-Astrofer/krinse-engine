package com.kerosene.kfe.paymentexecution.adapters.out.persistence;

import com.kerosene.kfe.adapters.out.persistence.model.wallet.KfeWalletKind;
import com.kerosene.kfe.adapters.out.persistence.model.wallet.KfeWalletStatus;
import com.kerosene.kfe.paymentexecution.application.port.out.ExecutionSourceWalletPort;
import com.kerosene.kfe.paymentexecution.domain.model.ExecutionSourceWalletSnapshot;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

@Component
@Transactional(propagation = Propagation.MANDATORY)
public class JpaExecutionSourceWalletAdapter implements ExecutionSourceWalletPort {
    private final EntityManager entityManager;

    public JpaExecutionSourceWalletAdapter(EntityManager entityManager) { this.entityManager = entityManager; }

    @Override
    public Optional<ExecutionSourceWalletSnapshot> findOwned(long userId, UUID walletId) {
        if (userId <= 0 || walletId == null) { return Optional.empty(); }
        // Scalar values bypass stale managed entities. No new wallet/audit lock-order edge is introduced.
        return entityManager.createQuery("""
                select w.id, w.userId, w.label, w.asset, w.status, w.kind, w.spendable
                from KfeWalletEntity w where w.id = :id and w.userId = :owner
                """, Object[].class).setParameter("id", walletId).setParameter("owner", userId)
                .getResultList().stream().findFirst().map(row -> new ExecutionSourceWalletSnapshot(
                        (UUID) row[0], (Long) row[1], (String) row[2], (String) row[3],
                        row[4] == KfeWalletStatus.ACTIVE, row[5] == KfeWalletKind.WATCH_ONLY,
                        row[5] != null && Boolean.TRUE.equals(row[6])));
    }
}
