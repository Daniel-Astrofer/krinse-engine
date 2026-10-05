package com.kerosene.kfe.paymentexecution.config;

import com.kerosene.kfe.paymentexecution.application.port.out.IdempotencyReservationStore;
import com.kerosene.kfe.paymentexecution.application.port.out.PaymentIdempotencyQueryPort;
import com.kerosene.kfe.paymentexecution.application.usecase.GetIdempotentPaymentService;
import com.kerosene.kfe.paymentexecution.application.usecase.ReservePaymentIdempotencyService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class PaymentIdempotencyConfiguration {
    @Bean
    GetIdempotentPaymentService getIdempotentPaymentService(IdempotencyReservationStore store, PaymentIdempotencyQueryPort queries) {
        return new GetIdempotentPaymentService(store, queries);
    }
    @Bean
    ReservePaymentIdempotencyService reservePaymentIdempotencyService(IdempotencyReservationStore store, GetIdempotentPaymentService replay) {
        return new ReservePaymentIdempotencyService(store, replay);
    }
}
