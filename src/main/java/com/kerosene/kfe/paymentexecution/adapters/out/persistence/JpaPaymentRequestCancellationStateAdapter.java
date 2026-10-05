package com.kerosene.kfe.paymentexecution.adapters.out.persistence;

import com.kerosene.kfe.adapters.out.persistence.model.paymentrequest.KfePaymentRequestEntity;
import com.kerosene.kfe.paymentexecution.application.port.out.PaymentRequestCancellationStatePort;
import com.kerosene.kfe.paymentexecution.domain.exception.PaymentCancellationRejected;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentRail;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentRequestCancellationSnapshot;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentRequestCancellationStatus;
import com.kerosene.kfe.adapters.out.persistence.repository.paymentrequest.KfePaymentRequestRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/** Uses the request row refreshed by the caller's lock port; does not acquire locks out of order. */
@Component
@Transactional(propagation = Propagation.MANDATORY)
public class JpaPaymentRequestCancellationStateAdapter implements PaymentRequestCancellationStatePort {

    private final KfePaymentRequestRepository repository;

    public JpaPaymentRequestCancellationStateAdapter(KfePaymentRequestRepository repository) {
        this.repository = repository;
    }

    @Override
    public PaymentRequestCancellationSnapshot load(long userId, UUID id) {
        return snapshot(find(userId, id));
    }

    @Override
    public void markCancelled(PaymentRequestCancellationSnapshot previous) {
        var entity = find(previous.userId(), previous.id());
        if (!previous.cancellable() || !snapshot(entity).equals(previous)) {
            throw new PaymentCancellationRejected();
        }
        entity.cancel();
        repository.save(entity);
    }

    private KfePaymentRequestEntity find(long userId, UUID id) {
        if (userId <= 0L || id == null) {
            throw notFound();
        }
        var entity = repository.findByIdAndUserId(id, userId).orElseThrow(
                JpaPaymentRequestCancellationStateAdapter::notFound);
        if (!id.equals(entity.getId()) || !Long.valueOf(userId).equals(entity.getUserId())) {
            throw notFound();
        }
        return entity;
    }

    private static IllegalArgumentException notFound() {
        return new IllegalArgumentException("KFE payment request not found.");
    }

    private static PaymentRequestCancellationSnapshot snapshot(KfePaymentRequestEntity entity) {
        return new PaymentRequestCancellationSnapshot(
                entity.getId(), entity.getUserId(), entity.getWalletId(), entity.getPublicId(),
                PaymentRequestCancellationStatus.valueOf(entity.getStatus().name()),
                PaymentRail.valueOf(entity.getRail().name()), entity.getPaymentHash(),
                entity.getProviderReference(), entity.getPaymentRequest(), entity.getPaidTransactionId());
    }
}
