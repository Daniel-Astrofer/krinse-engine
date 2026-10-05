package com.kerosene.kfe.paymentexecution.adapters.out.persistence;

import com.kerosene.kfe.paymentexecution.application.port.out.PaymentRequestCancellationLockPort;
import com.kerosene.kfe.adapters.out.persistence.repository.paymentrequest.KfePaymentRequestRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.util.UUID;

@Component
@Transactional(propagation = Propagation.MANDATORY)
public class JpaPaymentRequestCancellationLockAdapter implements PaymentRequestCancellationLockPort {
    private final KfePaymentRequestRepository repository;
    private final EntityManager entityManager;

    public JpaPaymentRequestCancellationLockAdapter(
            KfePaymentRequestRepository repository, EntityManager entityManager) {
        this.repository = repository;
        this.entityManager = entityManager;
    }

    @Override
    public void lock(long userId, UUID paymentRequestId) {
        var request = repository.findByIdAndUserIdForUpdate(paymentRequestId, userId)
                .orElseThrow(() -> new IllegalArgumentException("KFE payment request not found."));
        entityManager.refresh(request, LockModeType.PESSIMISTIC_WRITE);
        if (!Long.valueOf(userId).equals(request.getUserId())) {
            throw new IllegalArgumentException("KFE payment request not found.");
        }
    }
}
