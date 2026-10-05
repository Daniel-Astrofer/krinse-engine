package com.kerosene.kfe.paymentexecution.adapters.out.execution;

import com.kerosene.kfe.audit.adapters.out.crypto.KfeHashService;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kerosene.kfe.bootstrap.config.persistence.KfeStringColumnCryptoService;
import com.kerosene.kfe.adapters.out.persistence.model.paymentexecution.KfeExecutionOutboxEntity;
import com.kerosene.kfe.adapters.out.rail.onchain.KfeOnchainPaymentGateway;
import com.kerosene.kfe.adapters.out.persistence.repository.paymentexecution.KfeExecutionOutboxRepository;
import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class KfePreparedExecutionServiceTest {

    private final KfeExecutionOutboxRepository repository = mock(KfeExecutionOutboxRepository.class);
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final KfePreparedExecutionService service = new KfePreparedExecutionService(
            repository,
            new KfeStringColumnCryptoService(
                    Base64.getEncoder().encodeToString(new byte[32]), "", "", false),
            new KfeHashService(),
            objectMapper);

    @Test
    void persistsAndReloadsExactPreparedTransaction() {
        KfeExecutionOutboxEntity outbox = claimedOutbox(UUID.randomUUID());
        when(repository.findByIdForUpdate(outbox.getId())).thenReturn(Optional.of(outbox));
        String txid = "12".repeat(32);
        KfeOnchainPaymentGateway.PreparedOnchainPayment payload =
                new KfeOnchainPaymentGateway.PreparedOnchainPayment(
                        "deadbeef", txid, 100L, "funded", "combined", "raw-hash",
                        List.of("signer"), "intent", "{}");

        service.persistIfAbsent(
                outbox.getId(), outbox.getTransactionId(), outbox.getClaimToken(),
                outbox.getOperation(), KfePreparedExecutionService.PayloadType.ONCHAIN,
                payload, txid, KfeOnchainPaymentGateway.PreparedOnchainPayment.class);
        KfePreparedExecutionService.StoredPayload<KfeOnchainPaymentGateway.PreparedOnchainPayment> loaded =
                service.load(
                                outbox.getId(), outbox.getTransactionId(), outbox.getClaimToken(),
                                outbox.getOperation(), KfePreparedExecutionService.PayloadType.ONCHAIN,
                                KfeOnchainPaymentGateway.PreparedOnchainPayment.class)
                        .orElseThrow();

        assertThat(loaded.payload()).isEqualTo(payload);
        assertThat(loaded.executionReference()).isEqualTo(txid);
        assertThat(outbox.getPreparedPayloadCiphertext()).doesNotContain("deadbeef");
    }

    @Test
    void rejectsCiphertextMovedToAnotherOutbox() {
        KfeExecutionOutboxEntity source = claimedOutbox(UUID.randomUUID());
        when(repository.findByIdForUpdate(source.getId())).thenReturn(Optional.of(source));
        String txid = "34".repeat(32);
        KfeOnchainPaymentGateway.PreparedOnchainPayment payload =
                new KfeOnchainPaymentGateway.PreparedOnchainPayment(
                        "cafebabe", txid, 100L, "funded", "combined", "raw-hash",
                        List.of("signer"), "intent", "{}");
        service.persistIfAbsent(
                source.getId(), source.getTransactionId(), source.getClaimToken(),
                source.getOperation(), KfePreparedExecutionService.PayloadType.ONCHAIN,
                payload, txid, KfeOnchainPaymentGateway.PreparedOnchainPayment.class);

        KfeExecutionOutboxEntity target = claimedOutbox(source.getTransactionId());
        target.setPreparedPayloadCiphertext(source.getPreparedPayloadCiphertext());
        target.setPreparedPayloadHash(source.getPreparedPayloadHash());
        target.setExecutionReference(source.getExecutionReference());
        when(repository.findByIdForUpdate(target.getId())).thenReturn(Optional.of(target));

        assertThatThrownBy(() -> service.load(
                target.getId(), target.getTransactionId(), target.getClaimToken(),
                target.getOperation(), KfePreparedExecutionService.PayloadType.ONCHAIN,
                KfeOnchainPaymentGateway.PreparedOnchainPayment.class))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("decryption failed");
    }

    private KfeExecutionOutboxEntity claimedOutbox(UUID transactionId) {
        KfeExecutionOutboxEntity outbox = new KfeExecutionOutboxEntity();
        outbox.setTransactionId(transactionId);
        outbox.setOperation("ONCHAIN_OUTBOUND");
        outbox.setStatus("PROCESSING");
        outbox.setClaimToken(UUID.randomUUID());
        outbox.setLeaseExpiresAt(LocalDateTime.now(ZoneOffset.UTC).plusMinutes(10));
        outbox.setPayloadHash("payload-hash");
        return outbox;
    }
}
