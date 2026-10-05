package com.kerosene.kfe.paymentexecution.adapters.in.http.mapping;

import org.junit.jupiter.api.Test;
import com.kerosene.kfe.paymentexecution.application.port.in.PaymentCancellationHintsUseCase;
import com.kerosene.kfe.paymentexecution.application.result.PaymentCancellationHints;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentExecutionId;
import com.kerosene.kfe.adapters.out.persistence.model.shared.KfeDirection;
import com.kerosene.kfe.adapters.out.persistence.model.shared.KfeRail;
import com.kerosene.kfe.adapters.out.persistence.model.paymentexecution.KfeTransactionEntity;
import com.kerosene.kfe.adapters.out.persistence.model.paymentexecution.KfeTransactionStatus;
import com.kerosene.kfe.adapters.out.persistence.repository.wallet.KfeWalletAddressRepository;
import com.kerosene.kfe.adapters.out.persistence.repository.wallet.KfeWalletRepository;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;

class KfeResponseMapperTest {

    private final PaymentCancellationHintsUseCase cancellation = mock(PaymentCancellationHintsUseCase.class);

    {
        when(cancellation.hintsFor(anyLong(), any())).thenReturn(PaymentCancellationHints.none());
    }

    private final KfeResponseMapper mapper = new KfeResponseMapper(
            mock(KfeWalletAddressRepository.class),
            mock(KfeWalletRepository.class),
            cancellation);

    @Test
    void mapsDurableExternalDetailsAndSenderPerspective() {
        UUID sourceWalletId = UUID.randomUUID();
        UUID destinationWalletId = UUID.randomUUID();
        KfeTransactionEntity tx = transaction(10L, sourceWalletId, destinationWalletId);

        var response = mapper.toTransactionResponse(tx, 10L);

        assertThat(response.walletId()).isEqualTo(sourceWalletId);
        assertThat(response.externalReference()).isEqualTo("bcrt1qdestination");
        assertThat(response.memo()).isEqualTo("invoice 42");
        // Taxonomy normalizes BITCOIN_CORE → CUSTODIAL_ONCHAIN for clients.
        assertThat(response.provider()).isEqualTo("CUSTODIAL_ONCHAIN");
        assertThat(response.providerReference()).isEqualTo("provider-reference");
        assertThat(response.paymentHash()).isEqualTo("payment-hash");
        assertThat(response.quorumProposalHash()).isNull();
        assertThat(response.quorumAckCount()).isZero();
    }

    @Test
    void cancellationHintsUseIndependentReadPortAndPreservePublicFields() {
        var tx = transaction(10L, UUID.randomUUID(), UUID.randomUUID());
        var requestId = UUID.randomUUID();
        when(cancellation.hintsFor(10L, new PaymentExecutionId(tx.getId())))
                .thenReturn(new PaymentCancellationHints(true, "PAYMENT_REQUEST", requestId, "public-link", "OPEN"));

        var response = mapper.toTransactionResponse(tx, 10L);

        assertThat(response.cancellable()).isTrue();
        assertThat(response.cancelTarget()).isEqualTo("PAYMENT_REQUEST");
        assertThat(response.paymentRequestId()).isEqualTo(requestId);
        assertThat(response.paymentRequestPublicId()).isEqualTo("public-link");
        assertThat(response.paymentRequestStatus()).isEqualTo("OPEN");
        verify(cancellation).hintsFor(10L, new PaymentExecutionId(tx.getId()));
    }

    @Test
    void unavailableHintsDoNotSuggestCancellationOrExposeRequestMetadata() {
        var tx = transaction(10L, UUID.randomUUID(), UUID.randomUUID());
        when(cancellation.hintsFor(10L, new PaymentExecutionId(tx.getId())))
                .thenThrow(new IllegalStateException("Query unavailable"));

        var response = mapper.toTransactionResponse(tx, 10L);

        assertThat(response.cancellable()).isFalse();
        assertThat(response.paymentRequestId()).isNull();
        assertThat(response.paymentRequestPublicId()).isNull();
        assertThat(response.paymentRequestStatus()).isNull();
    }

    @Test
    void mapsDestinationWalletForInternalReceiverPerspective() {
        UUID sourceWalletId = UUID.randomUUID();
        UUID destinationWalletId = UUID.randomUUID();
        KfeTransactionEntity tx = transaction(10L, sourceWalletId, destinationWalletId);

        var response = mapper.toTransactionResponse(tx, 20L);

        assertThat(response.walletId()).isEqualTo(destinationWalletId);
    }

    private KfeTransactionEntity transaction(Long userId, UUID sourceWalletId, UUID destinationWalletId) {
        KfeTransactionEntity tx = new KfeTransactionEntity();
        tx.setUserId(userId);
        tx.setIdempotencyKey("idempotency-key");
        tx.setStatus(KfeTransactionStatus.SETTLED);
        tx.setRail(KfeRail.INTERNAL);
        tx.setDirection(KfeDirection.INTERNAL);
        tx.setSourceWalletId(sourceWalletId);
        tx.setDestinationWalletId(destinationWalletId);
        tx.setExternalReference("bcrt1qdestination");
        tx.setMemo("invoice 42");
        tx.setProvider("BITCOIN_CORE");
        tx.setProviderReference("provider-reference");
        tx.setPaymentHash("payment-hash");
        return tx;
    }
}
