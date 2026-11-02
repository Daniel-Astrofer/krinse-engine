package com.kerosene.kfe.pricing.adapters.in;

import org.springframework.stereotype.Service;
import com.kerosene.kfe.adapters.out.persistence.model.shared.KfeDirection;
import com.kerosene.kfe.adapters.out.persistence.model.shared.KfeRail;
import com.kerosene.kfe.pricing.application.port.out.PricingPolicyPort;
import com.kerosene.kfe.pricing.domain.model.PaymentDirection;
import com.kerosene.kfe.pricing.domain.model.PaymentRail;
import com.kerosene.kfe.pricing.domain.model.PricingQuote;
import com.kerosene.kfe.pricing.domain.model.SatoshiAmount;
import com.kerosene.kfe.pricing.domain.service.PricingCalculator;

/** Compatibility facade that translates legacy rail/direction enums into pricing domain values. */
@Service
public class KfePricingService {

    /** Versioned policy provider for the requested transaction dimensions. */
    private final PricingPolicyPort policy;
    /** Pure calculation service shared with the application quote use case. */
    private final PricingCalculator calculator;

    /** Creates the facade with its policy source and calculator. */
    public KfePricingService(PricingPolicyPort policy, PricingCalculator calculator) {
        this.policy = policy;
        this.calculator = calculator;
    }

    /** Calculates legacy-shaped quote amounts while preserving the policy version used. */
    public Quote quote(KfeRail rail, KfeDirection direction, long amountSats, long networkFeeSats) {
        PaymentRail domainRail = PaymentRail.valueOf(rail.name());
        PaymentDirection domainDirection = PaymentDirection.valueOf(direction.name());
        PricingQuote result = calculator.quote(
                domainRail,
                domainDirection,
                SatoshiAmount.positive(amountSats),
                new SatoshiAmount(networkFeeSats),
                policy.snapshot(domainRail, domainDirection));
        return new Quote(
                result.grossAmount().value(),
                result.receiverAmount().value(),
                result.networkFee().value(),
                result.totalDebit().value(),
                result.keroseneFee().value(),
                result.pricingPolicyVersion());
    }

    /**
     * Compatibility quote containing amount fields expected by existing callers.
     *
     * @param grossAmountSats amount before platform fee
     * @param receiverAmountSats amount credited to the recipient
     * @param networkFeeSats external rail fee
     * @param totalDebitSats sender debit, when applicable
     * @param keroseneFeeSats platform fee
     * @param pricingPolicyVersion version used by the calculation; zero for legacy constructors
     */
    public record Quote(
            long grossAmountSats,
            long receiverAmountSats,
            long networkFeeSats,
            long totalDebitSats,
            long keroseneFeeSats,
            int pricingPolicyVersion) {

        /** Preserves the original four-amount construction contract without policy metadata. */
        public Quote(long grossAmountSats, long receiverAmountSats, long networkFeeSats, long totalDebitSats) {
            this(grossAmountSats, receiverAmountSats, networkFeeSats, totalDebitSats, 0L, 0);
        }

        /** Preserves the five-amount contract while leaving policy metadata at its legacy default. */
        public Quote(long grossAmountSats, long receiverAmountSats, long networkFeeSats, long totalDebitSats, long keroseneFeeSats) {
            this(grossAmountSats, receiverAmountSats, networkFeeSats, totalDebitSats, keroseneFeeSats, 0);
        }
    }
}
