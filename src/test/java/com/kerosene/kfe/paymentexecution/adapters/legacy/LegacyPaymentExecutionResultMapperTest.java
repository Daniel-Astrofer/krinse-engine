package com.kerosene.kfe.paymentexecution.adapters.legacy;

import com.kerosene.kfe.adapters.in.http.dto.paymentexecution.KfeTransactionResponse;
import com.kerosene.kfe.adapters.out.persistence.model.shared.KfeDirection;
import com.kerosene.kfe.adapters.out.persistence.model.shared.KfeRail;
import com.kerosene.kfe.adapters.out.persistence.model.paymentexecution.KfeTransactionStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class LegacyPaymentExecutionResultMapperTest {
    @ParameterizedTest
    @EnumSource(KfeTransactionStatus.class)
    void preservesEveryResponseFieldAndLifecycleStatus(KfeTransactionStatus status) {
        var response = response(status, KfeRail.LIGHTNING, KfeDirection.OUTBOUND, true);
        assertThat(LegacyPaymentExecutionResultMapper.toLegacyResponse(LegacyPaymentExecutionResultMapper.toResult(response)))
                .isEqualTo(response);
    }

    @ParameterizedTest
    @EnumSource(KfeRail.class)
    void preservesEveryRailAndDirectionWithoutIntroducingNormalization(KfeRail rail) {
        for (var direction : KfeDirection.values()) {
            var response = response(KfeTransactionStatus.EXECUTING, rail, direction, true);
            assertThat(LegacyPaymentExecutionResultMapper.toLegacyResponse(LegacyPaymentExecutionResultMapper.toResult(response)))
                    .isEqualTo(response);
        }
    }

    @Test
    void preservesOptionalNullFieldsAndFalseFlags() {
        var response = response(KfeTransactionStatus.SETTLED, KfeRail.INTERNAL, KfeDirection.INTERNAL, false);
        assertThat(LegacyPaymentExecutionResultMapper.toLegacyResponse(LegacyPaymentExecutionResultMapper.toResult(response)))
                .isEqualTo(response);
    }

    private static KfeTransactionResponse response(KfeTransactionStatus status, KfeRail rail,
            KfeDirection direction, boolean full) {
        return new KfeTransactionResponse(UUID.randomUUID(), status, "display", "product", rail, direction,
                UUID.randomUUID(), full ? UUID.randomUUID() : null, full ? UUID.randomUUID() : null,
                full ? "wallet" : null, full ? "source" : null, full ? "destination" : null,
                full ? "counterparty" : null, 10_000L, 9_900L, 100L, 90L, 10_100L,
                full ? new BigDecimal("101.1") : null, full ? new BigDecimal("102.2") : null,
                full ? new BigDecimal("103.3") : null, full ? new BigDecimal("104.4") : null,
                full ? new BigDecimal("105.5") : null, full ? new BigDecimal("106.6") : null,
                full ? "quorum" : null, full ? 3 : 0, full ? " provider " : null,
                full ? "provider-reference" : null, full ? " external-reference " : null,
                full ? " memo ç | " : null, full ? "blockchain" : null, full ? "payment-hash" : null,
                full ? 6 : 0, full ? "failure-code" : null, full ? "failure-message" : null,
                full ? Instant.parse("2026-09-13T01:02:03Z") : null,
                full ? Instant.parse("2026-09-13T04:05:06Z") : null, full, full ? "cancel-target" : null,
                full ? UUID.randomUUID() : null, full ? "request-public-id" : null,
                full ? "request-status" : null, full ? "business" : null,
                full ? "network" : null, full ? "accounting" : null);
    }
}
