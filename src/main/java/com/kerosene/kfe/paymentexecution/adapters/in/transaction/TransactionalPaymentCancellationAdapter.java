package com.kerosene.kfe.paymentexecution.adapters.in.transaction;

import com.kerosene.kfe.paymentexecution.application.command.CancelPaymentCommand;
import com.kerosene.kfe.paymentexecution.application.command.CancelPaymentRequestCommand;
import com.kerosene.kfe.paymentexecution.application.port.in.CancelPaymentUseCase;
import com.kerosene.kfe.paymentexecution.application.port.in.CancelPaymentRequestUseCase;
import com.kerosene.kfe.paymentexecution.application.result.PaymentExecutionResult;
import com.kerosene.kfe.paymentexecution.application.usecase.CancelPaymentService;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import java.util.UUID;

/** Owns the transaction spanning request lock, outbox fence and all local financial effects. */
@Component
@Transactional
public class TransactionalPaymentCancellationAdapter implements CancelPaymentUseCase, CancelPaymentRequestUseCase {
    /** Cancellation policy and persistence workflow executed in this transaction. */
    private final CancelPaymentService service;

    /** Wires the application service whose transaction semantics this adapter enforces. */
    public TransactionalPaymentCancellationAdapter(CancelPaymentService service) {
        this.service = service;
    }

    /** Cancels an execution under the adapter-owned transaction. */
    @Override
    public PaymentExecutionResult cancel(CancelPaymentCommand command) {
        return service.cancel(command);
    }

    /** Cancels a payment request under the same transaction boundary. */
    @Override
    public UUID cancelPaymentRequest(CancelPaymentRequestCommand command) {
        return service.cancelPaymentRequest(command);
    }
}
