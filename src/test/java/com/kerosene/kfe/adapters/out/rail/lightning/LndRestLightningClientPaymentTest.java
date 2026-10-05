package com.kerosene.kfe.adapters.out.rail.lightning;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kerosene.kfe.adapters.out.rail.custody.CustodyGateway;
import com.kerosene.kfe.adapters.out.rail.lnurl.LnurlPayResolver;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LndRestLightningClientPaymentTest {

    @Test
    void parsesFixedBolt11AmountsFromHrp() {
        // 1 mBTC = 100_000 sats
        assertThat(LndRestLightningClient.parseBolt11AmountSatsFromHrp(
                "lntb1m1p49n88hpp5j50pdjyzl5rvxh6v6f28lzhltvswwkrvpyp93zlht0d"))
                .isEqualTo(100_000L);
        // 800 uBTC = 80_000 sats
        assertThat(LndRestLightningClient.parseBolt11AmountSatsFromHrp(
                "lntb800u1p49jmrupp5rscdcktt046suhys2hxfhf7n07lu68sgcfu2ss8md"))
                .isEqualTo(80_000L);
        // 200 uBTC = 20_000 sats
        assertThat(LndRestLightningClient.parseBolt11AmountSatsFromHrp(
                "lntb200u1p49jmt6pp5wat3awf2c7clcz68vlpvfs9gfzp20fh5wu3yshkre"))
                .isEqualTo(20_000L);
        // 306_610 nBTC = 30_661 sats
        assertThat(LndRestLightningClient.parseBolt11AmountSatsFromHrp(
                "lntb306610n1p49jm3dpp56whkqghslwlm9x3930u7j427ldd6j3adwrfxz5"))
                .isEqualTo(30_661L);
    }

    @Test
    void amountlessBolt11HasZeroHrpAmount() {
        assertThat(LndRestLightningClient.parseBolt11AmountSatsFromHrp(
                "lntb1p49n88hpp5j50pdjyzl5rvxh6v6f28lzhltvswwkrvpyp93zlht0d"))
                .isZero();
    }

    @Test
    void classifiesPermanentLightningErrors() {
        assertThat(LndRestLightningClient.isPermanentLightningClientError(
                "invoice expired. Valid until 2026-07-16"))
                .isTrue();
        assertThat(LndRestLightningClient.isPermanentLightningClientError(
                "amount must not be specified when paying a non-zero amount invoice"))
                .isTrue();
        assertThat(LndRestLightningClient.isPermanentLightningClientError(
                "connection refused"))
                .isFalse();
        assertThat(LndRestLightningClient.isPermanentLightningClientError(
                "payment attempt not completed before timeout"))
                .isFalse();
    }

    @Test
    void reconcilesSucceededPaymentWithoutSendingItAgain() {
        String paymentHash = "ab".repeat(32);
        RestTemplate restTemplate = mock(RestTemplate.class);
        when(restTemplate.exchange(
                contains("/v1/payreq/"),
                eq(HttpMethod.GET),
                any(HttpEntity.class),
                eq(String.class)))
                .thenReturn(ResponseEntity.ok("{\"payment_hash\":\"" + paymentHash
                        + "\",\"num_satoshis\":\"1000\"}"));
        when(restTemplate.exchange(
                contains("/v1/payments?"),
                eq(HttpMethod.GET),
                any(HttpEntity.class),
                eq(String.class)))
                .thenReturn(ResponseEntity.ok("{\"payments\":[{\"payment_hash\":\""
                        + paymentHash + "\",\"status\":\"SUCCEEDED\",\"fee_sat\":\"2\"}]}"));
        @SuppressWarnings("unchecked")
        ObjectProvider<LnurlPayResolver> resolver = mock(ObjectProvider.class);
        when(resolver.getIfAvailable()).thenReturn(null);
        LndRestLightningClient client = new LndRestLightningClient(
                restTemplate,
                new ObjectMapper(),
                "https://lnd.internal:8080",
                "00",
                30,
                3600,
                resolver);
        CustodyGateway.LightningPaymentCommand command = new CustodyGateway.LightningPaymentCommand(
                42L,
                null,
                "wallet",
                "lntb10u1p49n88hpp5j50pdjyzl5rvxh6v6f28lzhltvswwkrvpyp93zlht0d",
                1_000L,
                20L,
                "memo",
                "idempotency-key",
                "proof");

        LightningPaymentGateway.PreparedLightningPayment prepared = client.prepareLightning(command);
        CustodyGateway.PaymentResult result = client.payPreparedLightning(prepared);

        assertThat(result.paymentHash()).isEqualTo(paymentHash);
        assertThat(result.status()).isEqualTo("SUCCEEDED");
        verify(restTemplate, never()).exchange(
                contains("/v2/router/send"),
                eq(HttpMethod.POST),
                any(HttpEntity.class),
                eq(String.class));
    }
}
