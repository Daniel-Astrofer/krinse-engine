package com.kerosene.kfe.paymentexecution.adapters.out.persistence;

import com.kerosene.kfe.adapters.out.persistence.model.shared.KfeDirection;
import com.kerosene.kfe.adapters.out.persistence.model.shared.KfeRail;
import com.kerosene.kfe.adapters.out.persistence.model.paymentexecution.KfeTransactionEntity;
import com.kerosene.kfe.paymentexecution.application.port.out.PaymentIntentStore;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentExecutionId;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentIntent;
import com.kerosene.kfe.adapters.out.persistence.repository.paymentexecution.KfeTransactionRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Persists the legacy INTENT representation without opening an independent financial transaction. */
@Component
@Transactional(propagation = Propagation.MANDATORY)
public class JpaPaymentIntentStoreAdapter implements PaymentIntentStore {

    private final KfeTransactionRepository repository;

    public JpaPaymentIntentStoreAdapter(KfeTransactionRepository repository) {
        this.repository = repository;
    }

    @Override
    public PaymentExecutionId create(PaymentIntent intent) {
        var entity = new KfeTransactionEntity();
        entity.setUserId(intent.userId());
        entity.setIdempotencyKey(intent.idempotencyKey().value());
        entity.setRail(KfeRail.valueOf(intent.rail().name()));
        entity.setDirection(KfeDirection.valueOf(intent.direction().name()));
        entity.setSourceWalletId(intent.sourceWalletId());
        entity.setDestinationWalletId(intent.destinationWalletId());
        entity.setExternalReference(intent.externalReference());
        entity.setMemo(intent.memo());
        entity.setGrossAmountSats(intent.amountSats());
        return new PaymentExecutionId(repository.save(entity).getId());
    }
}
