package com.kerosene.kfe.liquidity.adapters.out.lightning;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import com.kerosene.kfe.adapters.out.rail.lightning.LightningClient;

import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Anti channel-jamming checks for Lightning outbound (doc V_NO_JAMMING).
 *
 * <ul>
 *   <li>Global pending HTLC ceiling across all channels</li>
 *   <li>Peer denylist: refuse outbound when a denylisted peer still holds pending HTLCs</li>
 * </ul>
 */
@Service
public class KfeLightningJammingGuard {

    private final ObjectProvider<LightningClient> lightningClientProvider;
    private final int maxPendingHtlcs;
    private final Set<String> peerDenylist;
    private final boolean enforceWhenUnprobeable;

    public KfeLightningJammingGuard(
            ObjectProvider<LightningClient> lightningClientProvider,
            @Value("${kfe.lightning.max-pending-htlcs:48}") int maxPendingHtlcs,
            @Value("${kfe.lightning.peer-denylist:}") String peerDenylistCsv,
            @Value("${kfe.lightning.jamming.enforce-when-unprobeable:false}")
            boolean enforceWhenUnprobeable) {
        this.lightningClientProvider = lightningClientProvider;
        this.maxPendingHtlcs = Math.max(0, maxPendingHtlcs);
        this.peerDenylist = parseDenylist(peerDenylistCsv);
        this.enforceWhenUnprobeable = enforceWhenUnprobeable;
    }

    public JammingCheck evaluate() {
        LightningClient client = lightningClientProvider.getIfAvailable();
        if (client == null) {
            return unprobeable("LIGHTNING_CLIENT_UNAVAILABLE");
        }
        int pending = client.pendingHtlcCount();
        if (pending < 0) {
            return unprobeable("PENDING_HTLC_UNAVAILABLE");
        }
        if (maxPendingHtlcs > 0 && pending >= maxPendingHtlcs) {
            return JammingCheck.blocked("PENDING_HTLC_LIMIT:" + pending + ">=" + maxPendingHtlcs);
        }
        if (!peerDenylist.isEmpty()) {
            Set<String> jammedPeers = client.peersWithPendingHtlcs().stream()
                    .map(peer -> peer.toLowerCase(Locale.ROOT))
                    .filter(peerDenylist::contains)
                    .collect(Collectors.toSet());
            if (!jammedPeers.isEmpty()) {
                return JammingCheck.blocked("DENYLIST_PEER_PENDING_HTLC:" + jammedPeers.iterator().next());
            }
        }
        return JammingCheck.allowed("HTLC_OK:" + pending);
    }

    private JammingCheck unprobeable(String reason) {
        if (enforceWhenUnprobeable) {
            return JammingCheck.blocked(reason);
        }
        return JammingCheck.softPass("BETA_LIMITED:" + reason);
    }

    private static Set<String> parseDenylist(String csv) {
        if (csv == null || csv.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .map(value -> value.toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
    }

    public record JammingCheck(boolean allowed, boolean hardBlock, String reason) {
        public static JammingCheck allowed(String reason) {
            return new JammingCheck(true, false, reason);
        }

        public static JammingCheck softPass(String reason) {
            return new JammingCheck(true, false, reason);
        }

        public static JammingCheck blocked(String reason) {
            return new JammingCheck(false, true, reason);
        }
    }
}
