package com.kerosene.kfe.paymentexecution.adapters.out.persistence;

import com.kerosene.kfe.paymentexecution.application.port.out.InternalPaymentSettlementStatePort;
import com.kerosene.kfe.paymentexecution.domain.model.ExecutionStatus;
import com.kerosene.kfe.paymentexecution.domain.model.InternalPaymentSettlementSnapshot;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentDirection;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentExecutionId;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentRail;
import com.kerosene.kfe.adapters.out.persistence.repository.paymentexecution.KfeTransactionRepository;
import com.kerosene.kfe.adapters.out.persistence.repository.wallet.KfeWalletRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Reads identity and amounts from persistence, never from caller-supplied wallet/amount metadata. */
@Component
@Transactional(propagation = Propagation.MANDATORY)
public class JpaInternalPaymentSettlementStateAdapter implements InternalPaymentSettlementStatePort {
    private final KfeTransactionRepository transactions;
    private final KfeWalletRepository wallets;
    private final EntityManager entityManager;

    public JpaInternalPaymentSettlementStateAdapter(
            KfeTransactionRepository transactions, KfeWalletRepository wallets, EntityManager entityManager) {
        this.transactions = transactions;
        this.wallets = wallets;
        this.entityManager = entityManager;
    }

    @Override
    public InternalPaymentSettlementSnapshot lockAndLoad(long userId, PaymentExecutionId executionId) {
        if (userId <= 0L || executionId == null) {
            throw new IllegalArgumentException("user id and execution id are required");
        }
        var tx = transactions.findByIdAndUserIdForUpdate(executionId.value(), userId)
                .orElseThrow(() -> new IllegalArgumentException("KFE transaction not found."));
        // The locking query flushes the caller's intent/quote first. Refresh stale managed reads
        // after acquiring the lock so a concurrent settlement cannot authorize a second debit.
        entityManager.refresh(tx, LockModeType.PESSIMISTIC_WRITE);
        if (!executionId.value().equals(tx.getId()) || !Long.valueOf(userId).equals(tx.getUserId())) {
            throw new IllegalArgumentException("KFE transaction not found.");
        }
        if (tx.getSourceWalletId() == null || tx.getDestinationWalletId() == null) {
            throw new IllegalStateException("Internal settlement wallets are required.");
        }
        var source = wallets.findByIdAndUserId(tx.getSourceWalletId(), userId)
                .orElseThrow(() -> new IllegalArgumentException("KFE source wallet not found."));
        if (!tx.getSourceWalletId().equals(source.getId()) || !Long.valueOf(userId).equals(source.getUserId())) {
            throw new IllegalArgumentException("KFE source wallet not found.");
        }
        var destination = wallets.findById(tx.getDestinationWalletId())
                .orElseThrow(() -> new IllegalArgumentException("KFE destination wallet not found."));
        if (!tx.getDestinationWalletId().equals(destination.getId()) || destination.getUserId() == null) {
            throw new IllegalArgumentException("KFE destination wallet not found.");
        }
        return new InternalPaymentSettlementSnapshot(executionId, userId,
                ExecutionStatus.valueOf(tx.getStatus().name()), PaymentRail.valueOf(tx.getRail().name()),
                PaymentDirection.valueOf(tx.getDirection().name()), source.getId(), destination.getId(),
                destination.getUserId(), tx.getTotalDebitSats(), tx.getReceiverAmountSats());
    }
}
