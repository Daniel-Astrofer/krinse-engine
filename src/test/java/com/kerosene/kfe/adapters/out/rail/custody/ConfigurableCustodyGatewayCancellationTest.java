package com.kerosene.kfe.adapters.out.rail.custody;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kerosene.common.exception.FinancialProviderUnavailableException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class ConfigurableCustodyGatewayCancellationTest {

    private static final String CANCEL_URL = "http://custody.test/api/v1/lightning/invoice/cancel";
    private final RestTemplate restTemplate = new RestTemplate();
    private final MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate);
    private final ConfigurableCustodyGateway gateway = new ConfigurableCustodyGateway(
            "TEST", "http://custody.test", "test-token", false,
            "/api/v1/onchain/address", "/api/v1/lightning/invoice", "/api/v1/lightning/invoice/status",
            "/api/v1/lightning/invoice/cancel", "/api/v1/onchain/send", "/api/v1/lightning/pay",
            restTemplate, new ObjectMapper());
    private final CustodyGateway.LightningInvoiceCancellationCommand command =
            new CustodyGateway.LightningInvoiceCancellationCommand(
                    7L, null, null, "payment-hash", "provider-reference", "ln-invoice");

    @Test
    void postsTheExistingWireContractWithoutInventingWalletIdentity() {
        server.expect(requestTo(CANCEL_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer test-token"))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(content().json("""
                        {
                          "userId":7,
                          "walletId":null,
                          "walletName":null,
                          "paymentHash":"payment-hash",
                          "reference":"provider-reference",
                          "paymentRequest":"ln-invoice"
                        }
                        """))
                .andRespond(withSuccess("{\"cancelled\":true}", MediaType.APPLICATION_JSON));

        assertThat(gateway.cancelLightningInvoice(command)).isTrue();

        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"cancelled\":true}",
            "{\"status\":\"CANCELLED\"}",
            "{\"status\":\"cancelled\"}"
    })
    void acceptsOnlyExplicitCancellationConfirmation(String response) {
        server.expect(requestTo(CANCEL_URL)).andRespond(withSuccess(response, MediaType.APPLICATION_JSON));

        assertThat(gateway.cancelLightningInvoice(command)).isTrue();

        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{}",
            "null",
            "[]",
            "{\"status\":null}",
            "{\"status\":\"\"}",
            "{\"status\":\"PENDING\"}",
            "{\"status\":\"SETTLED\"}",
            "{\"cancelled\":null}",
            "{\"cancelled\":\"true\"}",
            "{\"cancelled\":1}",
            "{\"cancelled\":false}",
            "{\"cancelled\":false,\"status\":\"CANCELLED\"}"
    })
    void rejectsAbsentMalformedOrExplicitlyNegativeConfirmation(String response) {
        server.expect(requestTo(CANCEL_URL)).andRespond(withSuccess(response, MediaType.APPLICATION_JSON));

        assertThat(gateway.cancelLightningInvoice(command)).isFalse();

        server.verify();
    }

    @Test
    void emptyHttpResponseCannotConfirmCancellation() {
        server.expect(requestTo(CANCEL_URL)).andRespond(withSuccess());

        assertThatThrownBy(() -> gateway.cancelLightningInvoice(command))
                .isInstanceOf(FinancialProviderUnavailableException.class);

        server.verify();
    }

    @Test
    void providerHttpFailureCannotConfirmCancellation() {
        server.expect(requestTo(CANCEL_URL)).andRespond(withServerError());

        assertThatThrownBy(() -> gateway.cancelLightningInvoice(command))
                .isInstanceOf(FinancialProviderUnavailableException.class);

        server.verify();
    }
}
