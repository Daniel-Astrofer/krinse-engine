package com.kerosene.kfe.pricing.adapters.in.http;

import com.kerosene.kfe.pricing.application.command.QuoteTransactionCommand;
import com.kerosene.kfe.pricing.application.port.in.QuoteTransactionUseCase;
import com.kerosene.kfe.pricing.application.result.FeeTierResult;
import com.kerosene.kfe.pricing.application.result.TransactionQuoteResult;
import com.kerosene.kfe.pricing.domain.model.PaymentDirection;
import com.kerosene.kfe.pricing.domain.model.PaymentRail;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TransactionQuoteControllerTest {

    @Test
    void mapsHttpRequestToUseCaseAndPreservesThePublicResponseShape() {
        Instant expiresAt = Instant.parse("2026-09-08T12:02:00Z");
        QuoteTransactionUseCase useCase = command -> resultFor(command, expiresAt);
        var controller = new TransactionQuoteController(useCase);

        var responseEntity = controller.quote(new TransactionQuoteRequest(
                PaymentRail.ONCHAIN,
                PaymentDirection.OUTBOUND,
                100_000L,
                0L));

        assertThat(responseEntity.getBody()).isNotNull();
        assertThat(responseEntity.getBody().isSuccess()).isTrue();
        TransactionQuoteResponse response = responseEntity.getBody().getData();
        assertThat(response.rail()).isEqualTo(PaymentRail.ONCHAIN);
        assertThat(response.direction()).isEqualTo(PaymentDirection.OUTBOUND);
        assertThat(response.totalDebitSats()).isEqualTo(103_060L);
        assertThat(response.totalFeeSats()).isEqualTo(3_060L);
        assertThat(response.quoteExpiresAt()).isEqualTo(expiresAt);
        assertThat(response.feeTiers()).singleElement().satisfies(tier -> {
            assertThat(tier.name()).isEqualTo("STANDARD");
            assertThat(tier.networkFeeSats()).isEqualTo(2_160L);
        });
    }

    private static TransactionQuoteResult resultFor(QuoteTransactionCommand command, Instant expiresAt) {
        return new TransactionQuoteResult(
                command.rail(),
                command.direction(),
                command.amountSats(),
                command.amountSats(),
                2_160L,
                103_060L,
                900L,
                3_060L,
                12L,
                180,
                3,
                1_800L,
                "BITCOIN_CORE",
                expiresAt,
                List.of(new FeeTierResult(
                        "STANDARD", 12L, 2_160L, 3, 1_800L, "BITCOIN_CORE")),
                1);
    }
}
