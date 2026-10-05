package com.kerosene.kfe.paymentexecution.adapters.out.crypto;

import com.kerosene.kfe.paymentexecution.domain.model.PaymentProposal;
import com.kerosene.kfe.audit.adapters.out.crypto.KfeHashService;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class LegacyPaymentProposalHashAdapterTest {
    @Test
    void delegatesCanonicalContentUnchangedWithoutNormalization() {
        var proposal = mock(PaymentProposal.class);
        var hashes = mock(KfeHashService.class);
        String content = "KFE_TX_PROPOSAL|opaque-id|null|  ref|é 🚀  |";
        when(proposal.canonicalContent()).thenReturn(content);
        when(hashes.sha256(content)).thenReturn("hash-value");

        assertThat(new LegacyPaymentProposalHashAdapter(hashes).hash(proposal)).isEqualTo("hash-value");
        verify(proposal).canonicalContent();
        verify(hashes).sha256(content);
        verifyNoMoreInteractions(proposal, hashes);
    }

    @Test
    void legacyHashUsesExactUtf8BytesAndLowercaseSha256() throws Exception {
        var proposal = mock(PaymentProposal.class);
        String content = "KFE_TX_PROPOSAL|7|ONCHAIN|OUTBOUND|null|référence 🚀|  pedido  ";
        when(proposal.canonicalContent()).thenReturn(content);
        String expected = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(content.getBytes(StandardCharsets.UTF_8)));

        assertThat(new LegacyPaymentProposalHashAdapter(new KfeHashService()).hash(proposal))
                .isEqualTo(expected).matches("[a-f0-9]{64}");
    }

    @Test
    void hashingFailurePropagatesWithoutFallbackHash() {
        var proposal = mock(PaymentProposal.class);
        var hashes = mock(KfeHashService.class);
        when(proposal.canonicalContent()).thenReturn("canonical");
        var failure = new IllegalStateException("hashing unavailable");
        when(hashes.sha256("canonical")).thenThrow(failure);

        assertThatThrownBy(() -> new LegacyPaymentProposalHashAdapter(hashes).hash(proposal)).isSameAs(failure);
    }
}
