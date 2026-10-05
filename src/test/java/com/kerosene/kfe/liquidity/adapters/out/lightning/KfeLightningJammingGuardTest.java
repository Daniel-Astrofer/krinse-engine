package com.kerosene.kfe.liquidity.adapters.out.lightning;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import com.kerosene.kfe.adapters.out.rail.lightning.LightningClient;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class KfeLightningJammingGuardTest {

    @SuppressWarnings("unchecked")
    private final ObjectProvider<LightningClient> clientProvider = mock(ObjectProvider.class);
    private final LightningClient client = mock(LightningClient.class);

    @Test
    void blocksWhenPendingHtlcLimitExceeded() {
        when(clientProvider.getIfAvailable()).thenReturn(client);
        when(client.pendingHtlcCount()).thenReturn(48);
        when(client.peersWithPendingHtlcs()).thenReturn(Set.of());

        KfeLightningJammingGuard guard =
                new KfeLightningJammingGuard(clientProvider, 48, "", false);

        KfeLightningJammingGuard.JammingCheck check = guard.evaluate();
        assertThat(check.allowed()).isFalse();
        assertThat(check.reason()).startsWith("PENDING_HTLC_LIMIT:");
    }

    @Test
    void blocksWhenDenylistedPeerHoldsPendingHtlc() {
        when(clientProvider.getIfAvailable()).thenReturn(client);
        when(client.pendingHtlcCount()).thenReturn(2);
        when(client.peersWithPendingHtlcs()).thenReturn(Set.of("abcpeer"));

        KfeLightningJammingGuard guard =
                new KfeLightningJammingGuard(clientProvider, 48, "ABCPEER,other", false);

        KfeLightningJammingGuard.JammingCheck check = guard.evaluate();
        assertThat(check.allowed()).isFalse();
        assertThat(check.reason()).startsWith("DENYLIST_PEER_PENDING_HTLC:");
    }

    @Test
    void allowsHealthyNode() {
        when(clientProvider.getIfAvailable()).thenReturn(client);
        when(client.pendingHtlcCount()).thenReturn(3);
        when(client.peersWithPendingHtlcs()).thenReturn(Set.of("honestpeer"));

        KfeLightningJammingGuard guard =
                new KfeLightningJammingGuard(clientProvider, 48, "badpeer", false);

        KfeLightningJammingGuard.JammingCheck check = guard.evaluate();
        assertThat(check.allowed()).isTrue();
        assertThat(check.reason()).startsWith("HTLC_OK:");
    }
}
