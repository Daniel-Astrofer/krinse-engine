package com.kerosene.kfe.paymentexecution.adapters.out.rail;

import com.kerosene.common.exception.ErrorCodes;
import com.kerosene.common.exception.StructuredPlatformException;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentRail;
import com.kerosene.kfe.wallet.adapters.out.bitcoin.BitcoinAddressValidator;
import com.kerosene.kfe.paymentrequest.adapters.in.KfePlatformLightningPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;

import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class LegacyPaymentDestinationValidationAdapterTest {
    private final BitcoinAddressValidator bitcoin = mock(BitcoinAddressValidator.class);
    private final KfePlatformLightningPolicy platform = mock(KfePlatformLightningPolicy.class);
    private final LegacyPaymentDestinationValidationAdapter adapter = new LegacyPaymentDestinationValidationAdapter(bitcoin, platform);

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\n\t"})
    void externalRailsRequireANonblankReferenceBeforeCallingDependencies(String reference) {
        for (var rail : new PaymentRail[] {PaymentRail.ONCHAIN, PaymentRail.LIGHTNING}) {
            assertThatThrownBy(() -> adapter.validate(rail, reference)).isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("externalReference is required for external outbound transactions.");
        }
        verifyNoInteractions(bitcoin, platform);
    }

    @Test
    void internalOrAbsentRailCannotBypassTheExternalBoundary() {
        assertThatThrownBy(() -> adapter.validate(PaymentRail.INTERNAL, "reference")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> adapter.validate(null, "reference")).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(bitcoin, platform);
    }

    @Test
    void validOnchainAddressUsesTheConfiguredNetworkWithoutChangingOriginalBytes() {
        String raw = " tb1qOriginalAddress ";
        when(bitcoin.isValidBitcoinAddressForConfiguredNetwork(raw)).thenReturn(true);

        adapter.validate(PaymentRail.ONCHAIN, raw);

        verify(bitcoin).isValidBitcoinAddressForConfiguredNetwork(raw);
        verifyNoMoreInteractions(bitcoin);
        verifyNoInteractions(platform);
    }

    @Test
    void invalidOnchainAddressPreservesTheExistingFailureAndDoesNotCheckLightningPolicy() {
        assertThatThrownBy(() -> adapter.validate(PaymentRail.ONCHAIN, "bad-address"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Invalid Bitcoin address format for externalReference.");
        verify(bitcoin).isValidBitcoinAddressForConfiguredNetwork("bad-address");
        verifyNoInteractions(platform);
    }

    @Test
    void bitcoinConfigurationFailurePropagatesWithoutFallbackToAnotherRail() {
        var failure = new IllegalStateException("Unsupported bitcoin.network");
        when(bitcoin.isValidBitcoinAddressForConfiguredNetwork(anyString())).thenThrow(failure);

        assertThatThrownBy(() -> adapter.validate(PaymentRail.ONCHAIN, "address")).isSameAs(failure);

        verifyNoInteractions(platform);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "lnbc1invoiceexample", "LNTB1INVOICEEXAMPLE", " lnbcrt1invoiceexample ",
            "lnurl1example", "lnurl1", "alice@example.com",
            "02aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            "LIGHTNING:lnbc1invoiceexample", "lightning://alice@example.com",
            "bitcoin:address?amount=0.1&lightning=lnbc1invoiceexample",
            "web+bitcoin:address?lightning=lightning%3Alnbc1invoiceexample",
            "\u200B lightning: lnbc1invoiceexample \n"
    })
    void acceptedLightningFormatsUseTheExistingClassifierAndPassRawReferenceToPlatformPolicy(String raw) {
        adapter.validate(PaymentRail.LIGHTNING, raw);

        verify(platform).rejectLightningOutboundIfPlatformOwned(raw);
        verifyNoMoreInteractions(platform);
        verifyNoInteractions(bitcoin);
    }

    @ParameterizedTest
    @ValueSource(strings = {"not-a-destination", "alice@localhost", "https://example.com", "01", "lnbc", "lnurl", "bc1bitcoinonly"})
    void invalidLightningFormatIsRejectedBeforePlatformLookup(String raw) {
        assertThatThrownBy(() -> adapter.validate(PaymentRail.LIGHTNING, raw))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Invalid Lightning destination for externalReference. "
                        + "Use a BOLT11 invoice (ln…), LNURL1…, Lightning Address (user@domain), "
                        + "or 66-char node pubkey (keysend).");
        verifyNoInteractions(platform, bitcoin);
    }

    @Test
    void platformOwnedLightningDenialPreservesTheStructured422AndItsPublicData() {
        String invoice = " lightning:lnbc1platforminvoice ";
        var error = new StructuredPlatformException("platform invoice", HttpStatus.UNPROCESSABLE_ENTITY,
                ErrorCodes.LEDGER_PLATFORM_LIGHTNING_DENIED,
                Map.of("publicId", "public-id", "suggestedRail", "INTERNAL", "suggestedDirection", "INTERNAL"));
        doThrow(error).when(platform).rejectLightningOutboundIfPlatformOwned(invoice);

        assertThatThrownBy(() -> adapter.validate(PaymentRail.LIGHTNING, invoice)).isSameAs(error);

        verify(platform).rejectLightningOutboundIfPlatformOwned(invoice);
        verifyNoMoreInteractions(platform);
        verifyNoInteractions(bitcoin);
    }

    @Test
    void platformLookupFailureCannotBeTreatedAsAnExternalInvoice() {
        var failure = new IllegalStateException("platform lookup failed");
        doThrow(failure).when(platform).rejectLightningOutboundIfPlatformOwned("lnbc1invoiceexample");

        assertThatThrownBy(() -> adapter.validate(PaymentRail.LIGHTNING, "lnbc1invoiceexample")).isSameAs(failure);

        verifyNoInteractions(bitcoin);
    }
}
