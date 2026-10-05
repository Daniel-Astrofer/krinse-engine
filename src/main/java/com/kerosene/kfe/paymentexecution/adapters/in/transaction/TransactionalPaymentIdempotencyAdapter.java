package com.kerosene.kfe.paymentexecution.adapters.in.transaction;

import com.kerosene.kfe.paymentexecution.application.command.ReservePaymentIdempotencyCommand;
import com.kerosene.kfe.paymentexecution.application.port.in.GetIdempotentPaymentUseCase;
import com.kerosene.kfe.paymentexecution.application.port.in.ReservePaymentIdempotencyUseCase;
import com.kerosene.kfe.paymentexecution.application.query.GetIdempotentPaymentQuery;
import com.kerosene.kfe.paymentexecution.application.result.PaymentExecutionResult;
import com.kerosene.kfe.paymentexecution.application.result.PaymentIdempotencyReservationResult;
import com.kerosene.kfe.paymentexecution.application.usecase.GetIdempotentPaymentService;
import com.kerosene.kfe.paymentexecution.application.usecase.ReservePaymentIdempotencyService;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.util.Optional;

/** Separates read-only idempotency lookup from reservation joined to the caller transaction. */
@Component
public class TransactionalPaymentIdempotencyAdapter implements GetIdempotentPaymentUseCase, ReservePaymentIdempotencyUseCase {
    /** Read-only lookup service for prior payments bound to an idempotency key. */
    private final GetIdempotentPaymentService queries;
    /** Transactional reservation service that binds the key to a single request fingerprint. */
    private final ReservePaymentIdempotencyService reservations;
    /** Wires the application service whose transaction semantics this adapter enforces. */
    public TransactionalPaymentIdempotencyAdapter(GetIdempotentPaymentService queries, ReservePaymentIdempotencyService reservations) {
        this.queries = queries;
        this.reservations = reservations;
    }
    /** Reads an owner-scoped idempotent payment result in a read-only transaction. */
    @Override
    @Transactional(readOnly = true)
    public Optional<PaymentExecutionResult> find(GetIdempotentPaymentQuery query) { return queries.find(query); }
    /** Reserves payment funds under the caller-owned submission transaction. */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public PaymentIdempotencyReservationResult reserve(ReservePaymentIdempotencyCommand command) { return reservations.reserve(command); }
}
