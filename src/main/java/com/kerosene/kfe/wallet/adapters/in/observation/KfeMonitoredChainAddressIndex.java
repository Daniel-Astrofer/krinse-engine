package com.kerosene.kfe.wallet.adapters.in.observation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import com.kerosene.common.service.AddressDerivationService;
import com.kerosene.kfe.adapters.out.persistence.model.paymentexecution.KfeTransactionEntity;
import com.kerosene.kfe.adapters.out.persistence.model.wallet.KfeWalletAddressEntity;
import com.kerosene.kfe.adapters.out.persistence.model.wallet.KfeWalletAddressStatus;
import com.kerosene.kfe.adapters.out.persistence.model.wallet.KfeWalletEntity;
import com.kerosene.kfe.adapters.out.persistence.model.wallet.KfeWalletKind;
import com.kerosene.kfe.adapters.out.persistence.model.wallet.KfeWalletStatus;
import com.kerosene.kfe.adapters.out.persistence.repository.paymentexecution.KfeTransactionRepository;
import com.kerosene.kfe.adapters.out.persistence.repository.wallet.KfeWalletAddressRepository;
import com.kerosene.kfe.adapters.out.persistence.repository.wallet.KfeWalletRepository;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * In-memory index of on-chain addresses belonging to active WATCH_ONLY wallets.
 * Used by the ZMQ reactive path to filter mempool/block activity without scanning
 * every wallet on every rawtx.
 */
@Component
public class KfeMonitoredChainAddressIndex {

    /** Logger for refresh and best-effort descriptor/funding-history expansion failures. */
    private static final Logger log = LoggerFactory.getLogger(KfeMonitoredChainAddressIndex.class);

    /** Enumerates active wallet kinds eligible for chain observation. */
    private final KfeWalletRepository walletRepository;
    /** Loads persisted receive/change addresses for monitored wallets. */
    private final KfeWalletAddressRepository addressRepository;
    /** Converts wallet xpubs into network-specific receive and change addresses. */
    private final AddressDerivationService addressDerivationService;
    /** Loads known cold-wallet inbound transactions whose funding spends should be detected. */
    private final KfeTransactionRepository transactionRepository;
    /** Number of external branch addresses derived per watch-only wallet, clamped to at least one. */
    private final int externalGap;
    /** Number of change branch addresses derived per watch-only wallet, clamped to zero or greater. */
    private final int changeGap;
    /** Atomically published immutable view used by ZMQ handlers without database access. */
    private final AtomicReference<Snapshot> snapshot =
            new AtomicReference<>(Snapshot.empty());

    /**
     * Creates the monitored-address index with configured external/change gap coverage.
     *
     * @param walletRepository wallet metadata repository
     * @param addressRepository persisted wallet address repository
     * @param addressDerivationService network-aware xpub derivation service
     * @param transactionRepository cold funding transaction lookup
     * @param externalGap external receive-branch derivation count, clamped to at least one
     * @param changeGap change-branch derivation count, clamped to zero or greater
     */
    public KfeMonitoredChainAddressIndex(
            KfeWalletRepository walletRepository,
            KfeWalletAddressRepository addressRepository,
            AddressDerivationService addressDerivationService,
            KfeTransactionRepository transactionRepository,
            @Value("${kfe.bitcoin.zmq.address-gap-external:${kfe.descriptor-scan-range:200}}")
                    int externalGap,
            @Value("${kfe.bitcoin.zmq.address-gap-change:${kfe.descriptor-scan-range:200}}")
                    int changeGap) {
        this.walletRepository = walletRepository;
        this.addressRepository = addressRepository;
        this.addressDerivationService = addressDerivationService;
        this.transactionRepository = transactionRepository;
        this.externalGap = Math.max(1, externalGap);
        this.changeGap = Math.max(0, changeGap);
    }

    /**
     * Periodically rebuilds the address snapshot; a failed refresh preserves the last published snapshot.
     */
    @Scheduled(
            fixedDelayString = "${kfe.bitcoin.zmq.address-cache-refresh-ms:30000}",
            initialDelayString = "8000")
    public void refresh() {
        try {
            rebuild();
        } catch (RuntimeException exception) {
            log.warn("[KFE ZMQ] address index refresh failed: {}", exception.getMessage());
        }
    }

    /**
     * Builds a replacement index from active watch-only, custodial on-chain, and internal wallets.
     * Persisted addresses and derived watch-only gap addresses are normalized; known cold inbound txids
     * are indexed for spend detection. Per-wallet derivation/history errors are logged and skipped, then
     * a complete immutable snapshot is atomically published for concurrent readers.
     */
    public void rebuild() {
        // Cold + custodial + INTERNAL receive addresses — ZMQ must see platform sinks too.
        List<KfeWalletEntity> monitoredWallets = walletRepository.findByKindInAndStatus(
                List.of(
                        KfeWalletKind.WATCH_ONLY,
                        KfeWalletKind.CUSTODIAL_ONCHAIN,
                        KfeWalletKind.INTERNAL),
                KfeWalletStatus.ACTIVE);
        Map<String, UUID> addressToWallet = new HashMap<>();
        Map<String, UUID> fundingTxidToWallet = new HashMap<>();
        Set<UUID> walletIds = new HashSet<>();
        for (KfeWalletEntity wallet : monitoredWallets) {
            walletIds.add(wallet.getId());
            List<KfeWalletAddressEntity> addresses =
                    addressRepository.findByWalletIdOrderByCreatedAtDesc(wallet.getId());
            for (KfeWalletAddressEntity row : addresses) {
                if (row.getStatus() != null && row.getStatus() != KfeWalletAddressStatus.ACTIVE) {
                    continue;
                }
                String address = normalize(row.getAddress());
                if (address == null) {
                    continue;
                }
                addressToWallet.putIfAbsent(address, wallet.getId());
            }
            if (wallet.getKind() == KfeWalletKind.WATCH_ONLY) {
                expandFromXpub(wallet, addressToWallet);
                // Known cold inbound funding txids — so ZMQ can detect Electrum spends of those
                // UTXOs even when no change returns to a monitored address.
                try {
                    List<KfeTransactionEntity> inbounds =
                            transactionRepository.findByDestinationWalletIdAndProvider(
                                    wallet.getId(),
                                    KfeColdWalletObservationService.PROVIDER_COLD_OBSERVER);
                    for (KfeTransactionEntity inbound : inbounds) {
                        String funding = inbound.getBlockchainTxid();
                        if (funding == null || funding.isBlank()) {
                            continue;
                        }
                        fundingTxidToWallet.putIfAbsent(
                                funding.trim().toLowerCase(Locale.ROOT), wallet.getId());
                    }
                } catch (RuntimeException exception) {
                    log.debug(
                            "[KFE ZMQ] funding txid index failed walletId={}: {}",
                            wallet.getId(),
                            exception.getMessage());
                }
            }
        }
        snapshot.set(new Snapshot(
                Collections.unmodifiableMap(addressToWallet),
                Collections.unmodifiableMap(fundingTxidToWallet),
                Collections.unmodifiableSet(walletIds)));
        log.debug(
                "[KFE ZMQ] address index rebuilt wallets={} addresses={} fundingTxids={}",
                walletIds.size(),
                addressToWallet.size(),
                fundingTxidToWallet.size());
    }

    /**
     * Returns all currently indexed wallet IDs (including custodial and internal sinks).
     *
     * @return immutable set of wallet IDs in the latest snapshot
     */
    public Set<UUID> allColdWalletIds() {
        return snapshot.get().walletIds();
    }

    /**
     * Resolves an address after trimming and case normalization.
     *
     * @param address Bitcoin address candidate
     * @return indexed wallet ID, or null for blank or unknown addresses
     */
    public UUID walletIdForAddress(String address) {
        String key = normalize(address);
        if (key == null) {
            return null;
        }
        return snapshot.get().addressToWallet().get(key);
    }

    /**
     * Resolves a collection of output addresses and deduplicates wallets receiving multiple outputs.
     *
     * @param addresses candidate output addresses
     * @return mutable set of matching wallet identifiers; empty when no address matches
     */
    public Set<UUID> walletIdsForAddresses(Iterable<String> addresses) {
        Set<UUID> hits = new HashSet<>();
        for (String address : addresses) {
            UUID walletId = walletIdForAddress(address);
            if (walletId != null) {
                hits.add(walletId);
            }
        }
        return hits;
    }

    /**
     * Resolves a known cold-wallet funding transaction ID using trimmed, case-insensitive matching.
     *
     * @param fundingTxid transaction ID referenced by a spending input
     * @return indexed wallet ID, or null for blank, unknown, or unavailable index data
     */
    public UUID walletIdForFundingTxid(String fundingTxid) {
        if (fundingTxid == null || fundingTxid.isBlank()) {
            return null;
        }
        Map<String, UUID> map = snapshot.get().fundingTxidToWallet();
        if (map == null || map.isEmpty()) {
            return null;
        }
        return map.get(fundingTxid.trim().toLowerCase(Locale.ROOT));
    }

    /**
     * Resolves funding transaction IDs referenced by transaction inputs and deduplicates wallet hits.
     *
     * @param fundingTxids candidate previous transaction IDs; null is treated as empty
     * @return set of wallets whose known funding outputs are being spent
     */
    public Set<UUID> walletIdsForFundingTxids(Iterable<String> fundingTxids) {
        Set<UUID> hits = new HashSet<>();
        if (fundingTxids == null) {
            return hits;
        }
        for (String txid : fundingTxids) {
            UUID walletId = walletIdForFundingTxid(txid);
            if (walletId != null) {
                hits.add(walletId);
            }
        }
        return hits;
    }

    /** @return number of normalized addresses in the current snapshot */
    public int addressCount() {
        return snapshot.get().addressToWallet().size();
    }

    /**
     * Derives external and change gap-window addresses for a watch-only wallet with an xpub.
     * Derivation errors are contained per wallet so other monitored wallets remain indexed.
     *
     * @param wallet watch-only wallet metadata
     * @param addressToWallet mutable candidate address index being built
     */
    private void expandFromXpub(KfeWalletEntity wallet, Map<String, UUID> addressToWallet) {
        String xpub = wallet.getXpub();
        if (xpub == null || xpub.isBlank()) {
            return;
        }
        try {
            String networkXpub = addressDerivationService.toNetworkExtendedPublicKey(xpub.trim());
            for (int i = 0; i < externalGap; i++) {
                putDerived(addressToWallet, wallet.getId(), networkXpub, i, false);
            }
            for (int i = 0; i < changeGap; i++) {
                putDerived(addressToWallet, wallet.getId(), networkXpub, i, true);
            }
        } catch (RuntimeException exception) {
            log.warn(
                    "[KFE ZMQ] xpub gap expand failed walletId={}: {}",
                    wallet.getId(),
                    exception.getMessage());
        }
    }

    /**
     * Derives one address and adds it to the candidate map if valid; duplicate addresses keep the first owner.
     * A provider failure for one derivation index is skipped without aborting the remaining gap window.
     *
     * @param addressToWallet mutable candidate index
     * @param walletId owning wallet identifier
     * @param networkXpub xpub converted for the active Bitcoin network
     * @param index derivation index within the selected branch
     * @param change true for change branch, false for external receive branch
     */
    private void putDerived(
            Map<String, UUID> addressToWallet,
            UUID walletId,
            String networkXpub,
            int index,
            boolean change) {
        try {
            String address = addressDerivationService.deriveAddressFromXpub(networkXpub, index, change);
            String key = normalize(address);
            if (key != null) {
                addressToWallet.putIfAbsent(key, walletId);
            }
        } catch (RuntimeException ignored) {
            // skip bad index
        }
    }

    /**
     * Canonicalizes lookup text by trimming and lowercasing; blank input has no index key.
     *
     * @param address candidate address
     * @return normalized key, or null for null/blank input
     */
    private static String normalize(String address) {
        if (address == null) {
            return null;
        }
        String trimmed = address.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        return trimmed.toLowerCase(Locale.ROOT);
    }

    /**
     * Immutable, atomically replaceable lookup tables used by concurrent observation callbacks.
     *
     * @param addressToWallet normalized address to owning wallet mapping
     * @param fundingTxidToWallet normalized known funding txid to cold wallet mapping
     * @param walletIds all wallets included in this observation snapshot
     */
    private record Snapshot(
            Map<String, UUID> addressToWallet,
            Map<String, UUID> fundingTxidToWallet,
            Set<UUID> walletIds) {
        /**
         * Creates the empty initial snapshot used before the first successful rebuild.
         *
         * @return snapshot containing no addresses, funding transactions, or wallets
         */
        static Snapshot empty() {
            return new Snapshot(Map.of(), Map.of(), Set.of());
        }
    }
}
