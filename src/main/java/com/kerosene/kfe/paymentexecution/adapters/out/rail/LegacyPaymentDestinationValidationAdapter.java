package com.kerosene.kfe.paymentexecution.adapters.out.rail;

import com.kerosene.kfe.paymentexecution.application.port.out.PaymentDestinationValidationPort;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentRail;
import com.kerosene.kfe.adapters.out.rail.lightning.LightningDestinationClassifier;
import com.kerosene.kfe.wallet.adapters.out.bitcoin.BitcoinAddressValidator;
import com.kerosene.kfe.paymentrequest.adapters.in.KfePlatformLightningPolicy;
import org.springframework.stereotype.Component;

/** External destination checks preserve configured network handling and the existing platform-invoice denial. */
@Component
public class LegacyPaymentDestinationValidationAdapter implements PaymentDestinationValidationPort {
    private final BitcoinAddressValidator bitcoinAddresses;
    private final KfePlatformLightningPolicy platformLightning;

    public LegacyPaymentDestinationValidationAdapter(BitcoinAddressValidator bitcoinAddresses,
            KfePlatformLightningPolicy platformLightning) {
        this.bitcoinAddresses = bitcoinAddresses;
        this.platformLightning = platformLightning;
    }

    @Override
    public void validate(PaymentRail rail, String externalReference) {
        if (rail != PaymentRail.ONCHAIN && rail != PaymentRail.LIGHTNING) {
            throw new IllegalArgumentException("An external payment rail is required for destination validation.");
        }
        if (externalReference == null || externalReference.isBlank()) {
            throw new IllegalArgumentException("externalReference is required for external outbound transactions.");
        }
        if (rail == PaymentRail.ONCHAIN) {
            if (!bitcoinAddresses.isValidBitcoinAddressForConfiguredNetwork(externalReference)) {
                throw new IllegalArgumentException("Invalid Bitcoin address format for externalReference.");
            }
            return;
        }
        if (!LightningDestinationClassifier.isValidLightningOutboundReference(externalReference)) {
            throw new IllegalArgumentException("Invalid Lightning destination for externalReference. "
                    + "Use a BOLT11 invoice (ln…), LNURL1…, Lightning Address (user@domain), "
                    + "or 66-char node pubkey (keysend).");
        }
        platformLightning.rejectLightningOutboundIfPlatformOwned(externalReference);
    }
}
