package com.kerosene.kfe.liquidity.application.port.in;

import java.util.UUID;

/**
 * Inbound boundary for reserving pooled Lightning liquidity around a payment.
 *
 * <p>The boundary deliberately exposes only payment identity and satoshi amounts;
 * persistence, Spring transactions, and Lightning clients remain outside the
 * liquidity application contract.</p>
 */
public interface LiquidityReservationUseCase {

    void reserveForTransaction(UUID transactionId, long amountSats);

    void consumeForTransaction(UUID transactionId);

    void releaseForTransaction(UUID transactionId);
}
