package com.kerosene.kfe.paymentexecution.application.port.out;

import com.kerosene.kfe.paymentexecution.domain.model.PaymentExecution;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentExecutionId;

import java.util.Optional;

/** Write-side repository for the PaymentExecution aggregate. */
public interface PaymentExecutionRepository {

    /** Loads the lifecycle aggregate by its execution identity. */
    /** @param id stable payment execution identity @return aggregate when present */
    Optional<PaymentExecution> findById(PaymentExecutionId id);

    /** Persists aggregate state within the caller's lifecycle transaction. */
    /** @param execution aggregate whose lifecycle state has changed */
    void save(PaymentExecution execution);
}
