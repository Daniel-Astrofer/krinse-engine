package com.kerosene.kfe.paymentexecution.adapters.in.legacy;

import com.kerosene.kfe.adapters.in.http.dto.paymentexecution.KfeSubmitTransactionRequest;
import com.kerosene.kfe.adapters.in.http.dto.paymentexecution.KfeTransactionResponse;
import com.kerosene.kfe.adapters.out.persistence.model.shared.KfeDirection;
import com.kerosene.kfe.adapters.out.persistence.model.shared.KfeRail;
import com.kerosene.kfe.adapters.out.persistence.model.paymentexecution.KfeTransactionStatus;
import com.kerosene.kfe.paymentexecution.application.command.SubmitPaymentCommand;
import com.kerosene.kfe.paymentexecution.domain.model.IdempotencyKey;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentDirection;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentRail;
import com.kerosene.kfe.paymentexecution.adapters.in.KfeTransactionEngine;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LegacySubmitPaymentAdapterTest {

    @Test
    void translatesWithoutLeakingLegacyDtosIntoTheInboundPort() {
        KfeTransactionEngine engine = mock(KfeTransactionEngine.class);
        KfeTransactionResponse response = mock(KfeTransactionResponse.class);
        UUID transactionId = UUID.randomUUID();
        when(response.id()).thenReturn(transactionId);
        when(response.status()).thenReturn(KfeTransactionStatus.EXECUTING);
        when(response.rail()).thenReturn(KfeRail.LIGHTNING);
        when(response.direction()).thenReturn(KfeDirection.OUTBOUND);
        when(engine.submit(
                org.mockito.ArgumentMatchers.eq(42L),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq("device-hash")))
                .thenReturn(response);
        var adapter = new LegacySubmitPaymentAdapter(engine);
        var command = new SubmitPaymentCommand(
                42L, new IdempotencyKey("key"), PaymentRail.LIGHTNING,
                PaymentDirection.OUTBOUND, UUID.randomUUID(), null,
                5_000L, 100L, "ln-invoice", "memo",
                "totp", "passkey", "passphrase", null, null,
                null, null, null, "device-hash");

        var result = adapter.submit(command);

        ArgumentCaptor<KfeSubmitTransactionRequest> legacy =
                ArgumentCaptor.forClass(KfeSubmitTransactionRequest.class);
        verify(engine).submit(org.mockito.ArgumentMatchers.eq(42L), legacy.capture(),
                org.mockito.ArgumentMatchers.eq("device-hash"));
        assertThat(legacy.getValue().idempotencyKey()).isEqualTo("key");
        assertThat(legacy.getValue().rail()).isEqualTo(KfeRail.LIGHTNING);
        assertThat(result.id()).isEqualTo(transactionId);
        assertThat(result.status().name()).isEqualTo("EXECUTING");
        assertThat(result.rail()).isEqualTo(PaymentRail.LIGHTNING);
    }
}
