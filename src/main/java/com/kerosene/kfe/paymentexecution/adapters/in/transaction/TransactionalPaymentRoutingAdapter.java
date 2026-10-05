package com.kerosene.kfe.paymentexecution.adapters.in.transaction;

import com.kerosene.kfe.paymentexecution.application.command.RouteLockedPaymentCommand;
import com.kerosene.kfe.paymentexecution.application.port.in.RouteLockedPaymentUseCase;
import com.kerosene.kfe.paymentexecution.application.result.PaymentRoutingResult;
import com.kerosene.kfe.paymentexecution.application.usecase.RouteLockedPaymentService;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Scheduling, status and statements join the reservation and request link in the owning submit. */
@Component
@Transactional(propagation = Propagation.MANDATORY)
public class TransactionalPaymentRoutingAdapter implements RouteLockedPaymentUseCase {
    /** Routing workflow delegated while the caller transaction remains active. */
    private final RouteLockedPaymentService service;
    /** Wires the application service whose transaction semantics this adapter enforces. */
    public TransactionalPaymentRoutingAdapter(RouteLockedPaymentService service) { this.service = service; }
    /** Routes a locked payment and schedules its durable execution command transactionally. */
    @Override
    public PaymentRoutingResult route(RouteLockedPaymentCommand command) { return service.route(command); }
}
