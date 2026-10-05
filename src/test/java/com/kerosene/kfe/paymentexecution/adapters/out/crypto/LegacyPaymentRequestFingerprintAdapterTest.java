package com.kerosene.kfe.paymentexecution.adapters.out.crypto;

import com.kerosene.kfe.paymentexecution.adapters.in.KfeTransactionIdempotencyUseCase;
import com.kerosene.kfe.adapters.in.http.dto.paymentexecution.KfeSubmitTransactionRequest;
import com.kerosene.kfe.adapters.out.persistence.model.shared.KfeDirection;
import com.kerosene.kfe.adapters.out.persistence.model.shared.KfeRail;
import com.kerosene.kfe.paymentexecution.adapters.legacy.LegacyPaymentSubmissionMapper;
import com.kerosene.kfe.paymentexecution.application.port.in.GetIdempotentPaymentUseCase;
import com.kerosene.kfe.audit.adapters.out.crypto.KfeHashService;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class LegacyPaymentRequestFingerprintAdapterTest {
    private final GetIdempotentPaymentUseCase queries = mock(GetIdempotentPaymentUseCase.class);
    private final LegacyPaymentRequestFingerprintAdapter adapter = new LegacyPaymentRequestFingerprintAdapter(
            new KfeTransactionIdempotencyUseCase(queries, new KfeHashService()));

    @Test
    void hashUsesExactLegacyWireBytesWithoutQueriesOrNormalization() throws Exception {
        UUID source = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID destination = UUID.fromString("00000000-0000-0000-0000-000000000002");
        var request = new KfeSubmitTransactionRequest("key", KfeRail.ONCHAIN, KfeDirection.OUTBOUND,
                source, destination, 1234L, 56L, " addr | á ", " memo | 漢 ",
                "otp", "assertion", "passphrase", "pin", " req | ", 25L, 3, "quote");
        String wire = "KFE_TX_REQUEST|41|ONCHAIN|OUTBOUND|" + source + "|" + destination
                + "|1234|56| addr | á | req | | memo | 漢 ";
        String expected = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(wire.getBytes(StandardCharsets.UTF_8)));
        assertThat(adapter.fingerprint(LegacyPaymentSubmissionMapper.toCommand(41L, request, "device")).value())
                .isEqualTo(expected);
        verifyNoInteractions(queries);
    }

    @Test
    void nullWalletsAndOptionalStringsUseOriginalNullAndEmptyConventions() throws Exception {
        var request = new KfeSubmitTransactionRequest("key", KfeRail.INTERNAL, KfeDirection.INTERNAL,
                null, null, 1L, 0L, null, null);
        var expected = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest("KFE_TX_REQUEST|1|INTERNAL|INTERNAL|null|null|1|0|||".getBytes(StandardCharsets.UTF_8)));
        assertThat(adapter.fingerprint(LegacyPaymentSubmissionMapper.toCommand(1L, request, null)).value()).isEqualTo(expected);
    }

    @Test
    void credentialsDeviceKeyAndQuoteHintsStayExcludedFromLegacyFingerprint() {
        var first = new KfeSubmitTransactionRequest("first", KfeRail.LIGHTNING, KfeDirection.OUTBOUND,
                null, null, 100L, 5L, "lnbc123", "memo", "otp1", "assert1", "phrase1", "pin1", "request", 2L, 3, "q1");
        var second = new KfeSubmitTransactionRequest("second", KfeRail.LIGHTNING, KfeDirection.OUTBOUND,
                null, null, 100L, 5L, "lnbc123", "memo", "otp2", "assert2", "phrase2", "pin2", "request", 4L, 6, "q2");
        assertThat(adapter.fingerprint(LegacyPaymentSubmissionMapper.toCommand(1L, first, "dev1")))
                .isEqualTo(adapter.fingerprint(LegacyPaymentSubmissionMapper.toCommand(1L, second, "dev2")));
        assertThat(adapter.fingerprint(LegacyPaymentSubmissionMapper.toCommand(2L, second, "dev2")))
                .isNotEqualTo(adapter.fingerprint(LegacyPaymentSubmissionMapper.toCommand(1L, first, "dev1")));
    }
}
