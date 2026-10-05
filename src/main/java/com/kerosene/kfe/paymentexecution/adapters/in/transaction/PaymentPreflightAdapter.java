package com.kerosene.kfe.paymentexecution.adapters.in.transaction;

import com.kerosene.kfe.paymentexecution.application.command.SubmitPaymentCommand;
import com.kerosene.kfe.paymentexecution.application.port.in.PreflightPaymentUseCase;
import com.kerosene.kfe.paymentexecution.application.result.PaymentPreflightResult;
import com.kerosene.kfe.paymentexecution.application.usecase.PreflightPaymentService;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Orchestration without an encompassing transaction; queries may own short transactions through their ports. */
@Component
public class PaymentPreflightAdapter implements PreflightPaymentUseCase {
    /** Preflight service that checks request eligibility and idempotent replay. */
    private final PreflightPaymentService service;

    /** Wires the application service whose transaction semantics this adapter enforces. */
    public PaymentPreflightAdapter(PreflightPaymentService service) {
        this.service = service;
    }

    /** Performs preflight outside an encompassing transaction so adapters can use short reads. */
    @Override
    public PaymentPreflightResult preflight(SubmitPaymentCommand command) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Payment preflight must start outside an existing transaction.");
        }
        return service.preflight(command);
    }
}
