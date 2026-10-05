package com.kerosene.kfe.pricing.application.usecase;

import com.kerosene.kfe.pricing.application.command.QuoteTransactionCommand;
import com.kerosene.kfe.pricing.application.port.out.NetworkFeePort;
import com.kerosene.kfe.pricing.application.result.FeeTierResult;
import com.kerosene.kfe.pricing.domain.model.PaymentDirection;
import com.kerosene.kfe.pricing.domain.model.PaymentRail;
import com.kerosene.kfe.pricing.domain.model.PricingPolicySnapshot;
import com.kerosene.kfe.pricing.domain.service.PricingCalculator;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class QuoteTransactionServiceTest {

    @Test
    void coordinatesOutboundPortsWithoutFrameworkTypes() {
        Instant expiresAt = Instant.parse("2026-09-08T12:02:00Z");
        var tier = new FeeTierResult("STANDARD", 12L, 2_160L, 3, 1_800L, "BITCOIN_CORE");
        NetworkFeePort networkFee = (rail, direction, requested) -> new NetworkFeePort.NetworkFeeEstimate(
                2_160L, 12L, 3, 1_800L, "BITCOIN_CORE", 180, expiresAt, List.of(tier));
        var service = new QuoteTransactionService(
                networkFee,
                (rail, direction) -> new PricingPolicySnapshot(
                        4,
                        new PricingPolicySnapshot.RailPricing(90, null, null)),
                new PricingCalculator());

        var result = service.quote(new QuoteTransactionCommand(
                PaymentRail.ONCHAIN,
                PaymentDirection.OUTBOUND,
                100_000L,
                0L));

        assertThat(result.networkFeeSats()).isEqualTo(2_160L);
        assertThat(result.keroseneFeeSats()).isEqualTo(900L);
        assertThat(result.totalDebitSats()).isEqualTo(103_060L);
        assertThat(result.totalFeeSats()).isEqualTo(3_060L);
        assertThat(result.quoteExpiresAt()).isEqualTo(expiresAt);
        assertThat(result.feeTiers()).containsExactly(tier);
        assertThat(result.pricingPolicyVersion()).isEqualTo(4);
    }
}
