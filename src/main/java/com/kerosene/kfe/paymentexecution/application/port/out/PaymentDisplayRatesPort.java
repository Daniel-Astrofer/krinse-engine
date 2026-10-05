package com.kerosene.kfe.paymentexecution.application.port.out;

import com.kerosene.kfe.paymentexecution.application.result.PaymentDisplayRates;

/** Reads fiat rates used only for presentation snapshots, never for ledger pricing. */
public interface PaymentDisplayRatesPort {

    /** @return current BTC/USD, EUR, and BRL display rates, including unavailable values */
    PaymentDisplayRates currentRates();
}
