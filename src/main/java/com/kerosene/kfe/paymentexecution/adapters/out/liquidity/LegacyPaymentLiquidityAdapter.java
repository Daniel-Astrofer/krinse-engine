package com.kerosene.kfe.paymentexecution.adapters.out.liquidity;

import com.kerosene.kfe.paymentexecution.application.port.out.PaymentLiquidityPort;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentExecutionId;
import com.kerosene.kfe.liquidity.adapters.out.persistence.KfeLightningLiquidityService;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

/** Keeps Lightning capacity reservations inside the payment's financial transaction. */
@Component
@Transactional(propagation = Propagation.MANDATORY)
public class LegacyPaymentLiquidityAdapter implements PaymentLiquidityPort {

    /** Existing Lightning capacity reservation service. */
    private final KfeLightningLiquidityService liquidityService;

    /** Creates the adapter with the existing liquidity reservation service. */
    public LegacyPaymentLiquidityAdapter(KfeLightningLiquidityService liquidityService) {
        this.liquidityService = liquidityService;
    }

    /** Reserves Lightning capacity under the payment execution identity in the caller transaction. */
    @Override
    public void reserve(PaymentExecutionId executionId, long amountSats) {
        Objects.requireNonNull(executionId, "payment execution id is required");
        liquidityService.reserveForTransaction(executionId.value(), amountSats);
    }

    /** Releases any reservation associated with the payment execution identity. */
    @Override
    public void release(PaymentExecutionId executionId) {
        Objects.requireNonNull(executionId, "payment execution id is required");
        liquidityService.releaseForTransaction(executionId.value());
    }
}
