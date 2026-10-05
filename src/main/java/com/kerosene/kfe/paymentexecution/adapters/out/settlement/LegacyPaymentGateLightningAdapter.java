package com.kerosene.kfe.paymentexecution.adapters.out.settlement;

import com.kerosene.kfe.paymentexecution.application.port.out.PaymentGateLightningPort;
import com.kerosene.kfe.paymentexecution.domain.model.SettlementJammingCheck;
import com.kerosene.kfe.liquidity.adapters.out.lightning.KfeLightningJammingGuard;
import com.kerosene.kfe.liquidity.adapters.out.persistence.KfeLightningLiquidityService;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Probes only: reservation remains a separate step after all gate flags pass. */
@Component
@Transactional(propagation = Propagation.MANDATORY)
public class LegacyPaymentGateLightningAdapter implements PaymentGateLightningPort {
    private final KfeLightningLiquidityService liquidity;
    private final KfeLightningJammingGuard jamming;
    public LegacyPaymentGateLightningAdapter(KfeLightningLiquidityService liquidity, KfeLightningJammingGuard jamming) {
        this.liquidity = liquidity; this.jamming = jamming;
    }
    @Override public boolean isLive() { return liquidity.isLive(); }
    @Override public long freeOutboundCapacitySats() { return liquidity.freeOutboundCapacitySats(); }
    @Override public boolean canCoverOutbound(long totalDebitSats) { return liquidity.canCoverOutbound(totalDebitSats); }
    @Override public boolean circuitBreakerOpen() { return liquidity.circuitBreakerOpen(); }
    @Override public SettlementJammingCheck evaluateJamming() {
        var result = jamming.evaluate();
        return new SettlementJammingCheck(result.allowed(), result.hardBlock(), result.reason());
    }
}
