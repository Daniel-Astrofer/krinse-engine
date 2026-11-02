package com.kerosene.kfe.adapters.out.rail.lightning;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kerosene.kfe.adapters.out.rail.channels.LightningChannelGateway.PreparedCircularRebalance;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.*;
import org.springframework.web.client.RestTemplate;
import org.mockito.ArgumentCaptor;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.assertj.core.api.Assertions.*;

class LndCircularRecoveryTest {
    private final RestTemplate rest=mock(RestTemplate.class);
    private final String hash="a".repeat(64), peer="02"+"b".repeat(64);
    @SuppressWarnings("unchecked")
    private LndRestLightningClient client() {
        return new LndRestLightningClient(rest,new ObjectMapper(),"https://lnd.internal:8080","00",30,3600,mock(ObjectProvider.class));
    }
    private PreparedCircularRebalance prepared() { return new PreparedCircularRebalance(hash,"ln-invoice",500,List.of("18446744073709551615"),peer); }
    @Test void routesToTargetPeerWithUnsignedChannelIdentity() throws Exception {
        when(rest.exchange(contains("/v2/router/send"),eq(HttpMethod.POST),any(HttpEntity.class),eq(String.class)))
                .thenReturn(ResponseEntity.ok("{\"status\":\"SUCCEEDED\",\"payment_hash\":\""+hash+"\",\"fee_sat\":\"0\"}"));
        assertThat(client().submitCircularRebalance(prepared()).succeeded()).isTrue();
        ArgumentCaptor<HttpEntity> request=ArgumentCaptor.forClass(HttpEntity.class);
        verify(rest).exchange(contains("/v2/router/send"),eq(HttpMethod.POST),request.capture(),eq(String.class));
        var body=new ObjectMapper().readTree(request.getValue().getBody().toString());
        assertThat(body.path("last_hop_pubkey").asText()).isEqualTo(Base64.getEncoder().encodeToString(HexFormat.of().parseHex(peer)));
        assertThat(body.path("outgoing_chan_ids").get(0).asText()).isEqualTo("18446744073709551615");
    }
    @ParameterizedTest @ValueSource(strings={"{}","{\"fee_sat\":\"bad\"}","{\"fee_sat\":-1}","{\"fee_sat\":1.5}"})
    void missingOrMalformedFeeRequiresReconciliation(String fields) {
        String extra=fields.equals("{}")?"":","+fields.substring(1,fields.length()-1);
        when(rest.exchange(contains("/v1/payments?"),eq(HttpMethod.GET),any(HttpEntity.class),eq(String.class)))
                .thenReturn(ResponseEntity.ok("{\"payments\":[{\"status\":\"SUCCEEDED\",\"payment_hash\":\""+hash+"\""+extra+"}]}"));
        assertThat(client().lookupCircularRebalance(hash).feeSats()).isEqualTo(-1);
        verify(rest,never()).exchange(anyString(),eq(HttpMethod.POST),any(HttpEntity.class),eq(String.class));
    }
    @Test void transportFailureCannotBecomeEmptyInventory() {
        when(rest.exchange(contains("/v1/channels"),eq(HttpMethod.GET),any(HttpEntity.class),eq(String.class))).thenThrow(new IllegalStateException("timeout"));
        assertThatThrownBy(() -> client().observeChannels()).isInstanceOf(IllegalStateException.class);
    }
    @Test void malformedInventoryCannotBecomeEmptyInventory() {
        when(rest.exchange(contains("/v1/channels"),eq(HttpMethod.GET),any(HttpEntity.class),eq(String.class))).thenReturn(ResponseEntity.ok("{}"));
        assertThatThrownBy(() -> client().observeChannels()).isInstanceOf(IllegalStateException.class);
    }
}
