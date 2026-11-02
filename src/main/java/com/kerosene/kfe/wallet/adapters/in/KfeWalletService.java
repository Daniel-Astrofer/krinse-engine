package com.kerosene.kfe.wallet.adapters.in;

import com.kerosene.kfe.audit.adapters.out.crypto.KfeHashService;
import com.kerosene.kfe.audit.adapters.out.persistence.KfeAuditLogService;
import com.kerosene.kfe.ledger.adapters.in.reconciliation.KfeOnchainBalanceSyncService;
import com.kerosene.kfe.ledger.adapters.out.persistence.balance.KfeBalanceService;
import com.kerosene.kfe.messaging.adapters.out.websocket.KfeDashboardPublisher;
import com.kerosene.kfe.paymentexecution.adapters.in.http.mapping.KfeResponseMapper;
import com.kerosene.kfe.paymentexecution.adapters.out.vault.KfeQuorumGateway;
import com.kerosene.kfe.wallet.adapters.in.observation.KfeColdWalletObservationService;
import com.kerosene.kfe.wallet.adapters.in.observation.KfeMonitoredChainAddressIndex;
import com.kerosene.kfe.wallet.adapters.out.bitcoin.KfeReceiveAddressIssuer;
import com.kerosene.kfe.wallet.adapters.out.vault.KfeMpcKeyService;

import java.time.ZoneOffset;


import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import com.kerosene.common.service.AddressDerivationService;
import com.kerosene.kfe.adapters.in.http.dto.wallet.KfeAddressResponse;
import com.kerosene.kfe.adapters.in.http.dto.wallet.KfeCreateWalletRequest;
import com.kerosene.kfe.adapters.in.http.dto.wallet.KfeUpdateWalletRequest;
import com.kerosene.kfe.adapters.in.http.dto.wallet.KfeWalletNameOption;
import com.kerosene.kfe.adapters.in.http.dto.wallet.KfeWalletResponse;
import com.kerosene.kfe.adapters.out.persistence.model.wallet.KfeWalletAddressEntity;
import com.kerosene.kfe.adapters.out.persistence.model.wallet.KfeWalletAddressRole;
import com.kerosene.kfe.adapters.out.persistence.model.wallet.KfeWalletAddressStatus;
import com.kerosene.kfe.adapters.out.persistence.model.wallet.KfeWalletEntity;
import com.kerosene.kfe.adapters.out.persistence.model.wallet.KfeWalletKind;
import com.kerosene.kfe.adapters.out.persistence.model.wallet.KfeWalletName;
import com.kerosene.kfe.adapters.out.persistence.model.wallet.KfeWalletStatus;
import com.kerosene.common.exception.FinancialProviderUnavailableException;
import com.kerosene.kfe.adapters.out.rail.onchain.BitcoinCoreRpcClient;
import com.kerosene.common.vaultmesh.settlement.VaultMeshDepositInfo;
import com.kerosene.common.vaultmesh.settlement.VaultMeshSettlementPort;
import com.kerosene.kfe.adapters.out.persistence.repository.wallet.KfeWalletAddressRepository;
import com.kerosene.kfe.adapters.out.persistence.repository.wallet.KfeWalletRepository;
import com.kerosene.kfe.wallet.domain.model.WalletKind;
import com.kerosene.kfe.wallet.domain.model.WalletStatus;
import com.kerosene.kfe.wallet.domain.exception.WalletRuleViolation;
import com.kerosene.kfe.wallet.domain.service.WalletLifecyclePolicy;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Coordinates wallet lifecycle, quorum/MPC provisioning, receive-address management, and post-commit chain observation. */
@Service
public class KfeWalletService {

    /** Logger for wallet provisioning, chain integration, and best-effort recovery failures. */
    private static final Logger log = LoggerFactory.getLogger(KfeWalletService.class);
    /** Ledger asset code used for every wallet balance created by this service. */
    private static final String ASSET_BTC = "BTC";
    /** Fixed participant-facing label for the internal assured wallet kind. */
    private static final String INTERNAL_GLOBAL_WALLET_LABEL = "Conta Assegurada";
    /** Maximum reason length persisted in wallet lifecycle failure metadata. */
    private static final int FAILURE_REASON_MAX_LENGTH = 180;
    /** Lifecycle states returned to users and counted when enforcing wallet capacity. */
    private static final List<KfeWalletStatus> USER_VISIBLE_WALLET_STATUSES = List.of(
            KfeWalletStatus.CREATING,
            KfeWalletStatus.ACTIVE,
            KfeWalletStatus.FROZEN,
            KfeWalletStatus.ROTATING_ADDRESS);
    /** States counted against the per-user wallet capacity policy. */
    private static final List<KfeWalletStatus> UNIQUE_WALLET_STATUSES = USER_VISIBLE_WALLET_STATUSES;

    /** Owner-scoped wallet persistence and row-lock boundary. */
    private final KfeWalletRepository walletRepository;
    /** Wallet receive-address persistence, reuse, retirement, and active-address lookup. */
    private final KfeWalletAddressRepository addressRepository;
    /** Creates empty wallet balances and updates balance projections. */
    private final KfeBalanceService balanceService;
    /** Hashes wallet policy and quorum proposal bindings without persisting raw proposal inputs. */
    private final KfeHashService hashService;
    /** Records wallet creation, address rotation, archive, update, and recovery events. */
    private final KfeAuditLogService auditLogService;
    /** Requires healthy unanimous Vault Mesh consensus for wallet lifecycle operations. */
    private final KfeQuorumGateway quorumGateway;
    /** Creates custodial wallet MPC public key material for the owner and wallet. */
    private final KfeMpcKeyService mpcKeyService;
    /** Maps persisted wallet and address entities into compatibility response DTOs. */
    private final KfeResponseMapper responseMapper;
    /** Schedules participant dashboard refresh after wallet transaction commit. */
    private final KfeDashboardPublisher dashboardPublisher;
    /** Derives receive addresses and network-correct extended public keys from public wallet material. */
    private final AddressDerivationService addressDerivationService;
    /** Provides configured external receive addresses when an xpub is not used. */
    private final KfeReceiveAddressIssuer receiveAddressIssuer;
    /** Optional source for the shared USERS Taproot deposit address. */
    private final ObjectProvider<VaultMeshSettlementPort> vaultMeshSettlementPort;
    /** Owns short wallet lifecycle transactions around database-only state changes. */
    private final TransactionTemplate transactionTemplate;
    /** Optional Bitcoin Core RPC integration for importing watch-only descriptors. */
    private final ObjectProvider<BitcoinCoreRpcClient> bitcoinCoreRpcClient;
    /** Optional chain-observed wallet balance synchronization after activation. */
    private final ObjectProvider<KfeOnchainBalanceSyncService> onchainBalanceSyncService;
    /** Optional initial history scan for non-custodial watch-only wallets. */
    private final ObjectProvider<KfeColdWalletObservationService> coldWalletObservationService;
    /** Optional rebuild of the address index consumed by the ZMQ watcher. */
    private final ObjectProvider<KfeMonitoredChainAddressIndex> monitoredChainAddressIndex;

    /** Supplies persistence, ledger, quorum, derivation, and optional chain-observation collaborators. */
    public KfeWalletService(
            KfeWalletRepository walletRepository,
            KfeWalletAddressRepository addressRepository,
            KfeBalanceService balanceService,
            KfeHashService hashService,
            KfeAuditLogService auditLogService,
            KfeQuorumGateway quorumGateway,
            KfeMpcKeyService mpcKeyService,
            KfeResponseMapper responseMapper,
            KfeDashboardPublisher dashboardPublisher,
            AddressDerivationService addressDerivationService,
            KfeReceiveAddressIssuer receiveAddressIssuer,
            ObjectProvider<VaultMeshSettlementPort> vaultMeshSettlementPort,
            TransactionTemplate transactionTemplate,
            ObjectProvider<BitcoinCoreRpcClient> bitcoinCoreRpcClient,
            ObjectProvider<KfeOnchainBalanceSyncService> onchainBalanceSyncService,
            ObjectProvider<KfeColdWalletObservationService> coldWalletObservationService,
            ObjectProvider<KfeMonitoredChainAddressIndex> monitoredChainAddressIndex) {
        this.walletRepository = walletRepository;
        this.addressRepository = addressRepository;
        this.balanceService = balanceService;
        this.hashService = hashService;
        this.auditLogService = auditLogService;
        this.quorumGateway = quorumGateway;
        this.mpcKeyService = mpcKeyService;
        this.responseMapper = responseMapper;
        this.dashboardPublisher = dashboardPublisher;
        this.addressDerivationService = addressDerivationService;
        this.receiveAddressIssuer = receiveAddressIssuer;
        this.vaultMeshSettlementPort = vaultMeshSettlementPort;
        this.transactionTemplate = transactionTemplate;
        this.bitcoinCoreRpcClient = bitcoinCoreRpcClient;
        this.onchainBalanceSyncService = onchainBalanceSyncService;
        this.coldWalletObservationService = coldWalletObservationService;
        this.monitoredChainAddressIndex = monitoredChainAddressIndex;
    }

    /** Validates a request, persists a pending wallet, obtains quorum and optional MPC key, then activates it in a second transaction. Chain RPC hooks run only after activation commits; failures are recorded without pretending activation succeeded. */
    public KfeWalletResponse createWallet(Long userId, KfeCreateWalletRequest request) {
        validateCreateRequest(request);

        PendingWallet pending = Objects.requireNonNull(transactionTemplate.execute(status ->
                createPendingWallet(userId, request)));
        String proposalHash = kfeWalletCreateProposalHash(userId, pending);
        KfeQuorumGateway.Result quorum = requireWalletCreateQuorum(userId, pending, proposalHash);
        String mpcPublicKey = provisionMpcPublicKey(userId, pending);

        final UUID walletId = pending.walletId();
        try {
            KfeWalletResponse response = Objects.requireNonNull(transactionTemplate.execute(status ->
                    activateWallet(userId, request, walletId, proposalHash, quorum, mpcPublicKey)));
            // Core RPC must run AFTER DB commit. Nested @Transactional failures
            // (scantxoutset / getdescriptorinfo) mark the outer TX rollback-only
            // even when exceptions are caught — surfacing as UnexpectedRollbackException.
            runPostActivationChainHooks(walletId);
            return response;
        } catch (RuntimeException exception) {
            markWalletCreationFailed(
                    userId,
                    walletId,
                    KfeWalletStatus.KEYGEN_FAILED,
                    "Wallet activation failed: " + safeReason(exception));
            throw exception;
        }
    }

    /** Checks capacity and creates the CREATING wallet and empty balance in the first database transaction. */
    private PendingWallet createPendingWallet(Long userId, KfeCreateWalletRequest request) {
        requireWalletCapacity(userId, request.kind());
        KfeWalletEntity wallet = new KfeWalletEntity();
        wallet.setUserId(userId);
        wallet.setKind(request.kind());
        wallet.setStatus(KfeWalletStatus.CREATING);
        wallet.setLabel(resolveWalletLabel(request));
        wallet.setAsset(ASSET_BTC);
        wallet.setSpendable(request.kind() != KfeWalletKind.WATCH_ONLY);
        wallet.setXpub(blankToNull(request.xpub()));
        wallet.setDescriptor(blankToNull(request.descriptor()));
        wallet.setFingerprint(blankToNull(request.fingerprint()));
        wallet.setDerivationPath(blankToNull(request.derivationPath()));
        wallet.setQuorumPolicyHash(quorumPolicyHash(request.kind()));
        wallet = walletRepository.save(wallet);
        balanceService.createEmptyBalance(wallet.getId(), wallet.getAsset());
        dashboardPublisher.publishAfterCommit(userId);
        return new PendingWallet(wallet.getId(), wallet.getKind(), wallet.getQuorumPolicyHash());
    }

    /** Applies the domain capacity policy to the current count of user-visible wallets of this kind. */
    private void requireWalletCapacity(Long userId, KfeWalletKind kind) {
        long activeWallets = walletRepository.countByUserIdAndKindAndStatusIn(userId, kind, UNIQUE_WALLET_STATUSES);
        WalletLifecyclePolicy.requireCapacity(WalletKind.valueOf(kind.name()), activeWallets);
    }

    /** Requires healthy unanimous consensus and records QUORUM_BLOCKED when the provider is unavailable. */
    private KfeQuorumGateway.Result requireWalletCreateQuorum(
            Long userId,
            PendingWallet pending,
            String proposalHash) {
        try {
            return quorumGateway.requireHealthyUnanimousConsensus(proposalHash);
        } catch (RuntimeException exception) {
            log.warn(
                    "[KFE Wallet] Wallet creation quorum unavailable walletId={} kind={}: {}",
                    pending.walletId(),
                    pending.kind(),
                    exception.getMessage());
            markWalletCreationFailed(
                    userId,
                    pending.walletId(),
                    KfeWalletStatus.QUORUM_BLOCKED,
                    "Quorum failed: " + safeReason(exception));
            throw new FinancialProviderUnavailableException(
                    "KFE wallet quorum is temporarily unavailable. Wallet creation was not completed.",
                    exception);
        }
    }

    /** Generates MPC key material only for custodial on-chain wallets and records KEYGEN_FAILED on errors. */
    private String provisionMpcPublicKey(Long userId, PendingWallet pending) {
        if (pending.kind() != KfeWalletKind.CUSTODIAL_ONCHAIN) {
            return null;
        }

        try {
            String publicKey = mpcKeyService.keygenWallet(pending.walletId(), userId);
            if (!hasText(publicKey)) {
                throw new IllegalStateException("MPC sidecar returned an empty public key.");
            }
            return publicKey;
        } catch (RuntimeException exception) {
            log.warn(
                    "[KFE Wallet] MPC key generation unavailable walletId={}: {}",
                    pending.walletId(),
                    exception.getMessage());
            markWalletCreationFailed(
                    userId,
                    pending.walletId(),
                    KfeWalletStatus.KEYGEN_FAILED,
                    "MPC key generation failed: " + safeReason(exception));
            throw new FinancialProviderUnavailableException(
                    "KFE MPC key generation is temporarily unavailable. Wallet creation was not completed.",
                    exception);
        }
    }

    /** Locks the owner-scoped pending row, marks it active, creates/derives an optional receive address, and records quorum audit evidence. */
    private KfeWalletResponse activateWallet(
            Long userId,
            KfeCreateWalletRequest request,
            UUID walletId,
            String proposalHash,
            KfeQuorumGateway.Result quorum,
            String mpcPublicKey) {
        KfeWalletEntity wallet = walletRepository.findByIdAndUserIdForUpdate(walletId, userId)
                .orElseThrow(() -> new IllegalArgumentException("KFE wallet not found."));
        wallet.setMpcPublicKey(mpcPublicKey);
        wallet.setStatus(KfeWalletStatus.ACTIVE);
        wallet = walletRepository.save(wallet);

        if (hasText(request.initialAddress())) {
            createProvidedAddress(wallet, request);
        } else if (Boolean.TRUE.equals(request.issueInitialAddress())
                || shouldAutoIssueWatchOnlyAddress(wallet, request)) {
            issueFreshAddress(wallet, false);
        }

        // Bitcoin Core import + observed balance sync intentionally deferred to
        // runPostActivationChainHooks() after this transaction commits.

        auditLogService.record(
                "KFE_WALLET_CREATED",
                null,
                wallet.getId(),
                null,
                null,
                Map.of(
                        "walletId", wallet.getId().toString(),
                        "kind", wallet.getKind().name(),
                        "proposalHash", proposalHash,
                        "quorumAckCount", quorum.acceptedNodes()));
        dashboardPublisher.publishAfterCommit(userId);
        return responseMapper.toWalletResponse(wallet);
    }

    /**
     * Best-effort chain hooks after wallet row is ACTIVE and committed.
     * Failures never undo wallet creation.
     *
     * <p>For WATCH_ONLY, monitoring starts at registration: balance probe + first history
     * observation (UTXOs already present + later scheduled scans). Pre-registration chain
     * history is intentionally out of scope without a full indexer.
     */
    private void runPostActivationChainHooks(UUID walletId) {
        KfeWalletEntity wallet = walletRepository.findById(walletId).orElse(null);
        if (wallet == null) {
            return;
        }
        importWatchOnlyIntoBitcoinCore(wallet);
        syncChainObservedBestEffort(wallet);
        startColdHistoryMonitoringBestEffort(wallet);
    }

    /** Synchronizes observed chain balance only for watch-only and custodial on-chain wallets; failures do not undo activation. */
    private void syncChainObservedBestEffort(KfeWalletEntity wallet) {
        if (wallet.getKind() != KfeWalletKind.WATCH_ONLY
                && wallet.getKind() != KfeWalletKind.CUSTODIAL_ONCHAIN) {
            return;
        }
        KfeOnchainBalanceSyncService sync = onchainBalanceSyncService.getIfAvailable();
        if (sync == null) {
            return;
        }
        try {
            sync.syncWallet(wallet.getId());
        } catch (RuntimeException exception) {
            log.warn(
                    "[KFE Wallet] Initial chain balance sync failed walletId={}: {}",
                    wallet.getId(),
                    exception.getMessage());
        } catch (Exception exception) {
            log.warn(
                    "[KFE Wallet] Initial chain balance sync failed walletId={}: {}",
                    wallet.getId(),
                    exception.getMessage());
        }
    }

    /**
     * Immediately indexes post-registration cold activity (current UTXOs as inbound
     * observations). Ongoing activity is picked up by {@link KfeColdWalletObservationService}
     * and PSBT broadcast hooks.
     */
    private void startColdHistoryMonitoringBestEffort(KfeWalletEntity wallet) {
        if (wallet.getKind() != KfeWalletKind.WATCH_ONLY) {
            return;
        }
        KfeColdWalletObservationService coldObs = coldWalletObservationService.getIfAvailable();
        if (coldObs == null) {
            return;
        }
        try {
            coldObs.observeWallet(wallet.getId());
            KfeMonitoredChainAddressIndex index = monitoredChainAddressIndex.getIfAvailable();
            if (index != null) {
                try {
                    index.rebuild();
                } catch (RuntimeException ignored) {
                    // ZMQ filter will refresh on its own schedule
                }
            }
            log.info(
                    "[KFE Wallet] Cold history monitoring started at registration walletId={}",
                    wallet.getId());
        } catch (RuntimeException exception) {
            log.warn(
                    "[KFE Wallet] Initial cold history observation failed walletId={}: {}",
                    wallet.getId(),
                    exception.getMessage());
        } catch (Exception exception) {
            log.warn(
                    "[KFE Wallet] Initial cold history observation failed walletId={}: {}",
                    wallet.getId(),
                    exception.getMessage());
        }
    }

    /**
     * Cold/watch-only wallets should always get a first receive address when an xpub
     * is present, even if the client forgot {@code issueInitialAddress=true}.
     */
    private boolean shouldAutoIssueWatchOnlyAddress(KfeWalletEntity wallet, KfeCreateWalletRequest request) {
        return wallet.getKind() == KfeWalletKind.WATCH_ONLY
                && hasText(wallet.getXpub())
                && !hasText(request.initialAddress());
    }

    /**
     * Best-effort Core import so listunspent / payment monitors can see cold funds.
     * Failure is logged but does not roll back wallet creation (client can still store xpub).
     */
    private void importWatchOnlyIntoBitcoinCore(KfeWalletEntity wallet) {
        if (wallet.getKind() != KfeWalletKind.WATCH_ONLY) {
            return;
        }
        BitcoinCoreRpcClient core = bitcoinCoreRpcClient.getIfAvailable();
        if (core == null) {
            log.warn(
                    "[KFE Wallet] Bitcoin Core RPC unavailable; watch-only descriptor not imported walletId={}",
                    wallet.getId());
            return;
        }
        String descriptor = resolveWatchOnlyDescriptor(wallet);
        if (!hasText(descriptor)) {
            log.warn(
                    "[KFE Wallet] No descriptor/xpub to import for watch-only walletId={}",
                    wallet.getId());
            return;
        }
        try {
            // Best-effort: shared hot Core wallets with private keys often reject
            // watch-only descriptor imports (-4). Balance still comes from scantxoutset.
            core.importWatchOnlyDescriptor(
                    descriptor,
                    java.time.LocalDateTime.ofInstant(
                            java.time.Instant.EPOCH,
                            java.time.ZoneOffset.UTC));
            log.info(
                    "[KFE Wallet] Imported watch-only descriptor into Bitcoin Core walletId={}",
                    wallet.getId());
        } catch (RuntimeException exception) {
            String msg = exception.getMessage() == null ? "" : exception.getMessage();
            if (msg.contains("private keys") || msg.contains("watch-only")) {
                log.info(
                        "[KFE Wallet] Core wallet cannot hold watch-only descriptors walletId={} — using scantxoutset for balance",
                        wallet.getId());
            } else {
                log.warn(
                        "[KFE Wallet] Failed to import watch-only descriptor walletId={}: {}",
                        wallet.getId(),
                        msg);
            }
        } catch (Exception exception) {
            log.warn(
                    "[KFE Wallet] Failed to import watch-only descriptor walletId={}: {}",
                    wallet.getId(),
                    exception.getMessage());
        }
    }

    /** Prefers a structurally usable stored descriptor, rebuilds it from xpub when necessary, and returns null when no public key material exists. */
    private String resolveWatchOnlyDescriptor(KfeWalletEntity wallet) {
        // Prefer rebuilding from xpub when the stored descriptor looks truncated/invalid.
        // Historical bug: StringCryptoConverter padded/truncated to 128 bytes and clipped
        // "/0/*)" off electrum descriptors (≈133+ chars), which Core then rejects.
        if (hasText(wallet.getDescriptor())) {
            String stored = rewriteDescriptorXpubsForNetwork(wallet.getDescriptor().trim());
            if (isUsableOutputDescriptor(stored)) {
                return stored;
            }
            log.warn(
                    "[KFE Wallet] Stored descriptor unusable for walletId={} (len={}); rebuilding from xpub",
                    wallet.getId(),
                    stored.length());
        }
        if (!hasText(wallet.getXpub())) {
            return null;
        }
        return buildWpkhReceiveDescriptor(wallet);
    }

    /** Builds a ranged native SegWit receive descriptor using normalized origin path and network xpub. */
    private String buildWpkhReceiveDescriptor(KfeWalletEntity wallet) {
        String fingerprint = hasText(wallet.getFingerprint())
                ? wallet.getFingerprint().trim()
                : "00000000";
        String accountPath = hasText(wallet.getDerivationPath())
                ? wallet.getDerivationPath().trim().replaceFirst("^m/", "").replace("'", "h")
                : "84h/0h/0h";
        // Electrum standard account path is bare "m" → omit path in origin if empty.
        String origin = accountPath.isEmpty() || "m".equalsIgnoreCase(accountPath)
                ? fingerprint
                : fingerprint + "/" + accountPath;
        String networkXpub = addressDerivationService.toNetworkExtendedPublicKey(wallet.getXpub().trim());
        return "wpkh([" + origin + "]" + networkXpub + "/0/*)";
    }

    /**
     * Minimal structural check before handing a descriptor to Bitcoin Core.
     * Truncated ciphertext decrypts to strings missing the closing {@code /*)} branch.
     */
    private static boolean isUsableOutputDescriptor(String descriptor) {
        if (descriptor == null || descriptor.isBlank()) {
            return false;
        }
        String bare = descriptor.trim();
        int hash = bare.indexOf('#');
        if (hash >= 0) {
            bare = bare.substring(0, hash);
        }
        if (!bare.contains("(") || !bare.endsWith(")")) {
            return false;
        }
        // Range descriptors we import always include a wildcard branch.
        if (bare.contains("/*") || bare.matches(".*\\)/\\d+\\)$")) {
            return true;
        }
        // addr(...) or raw single-key without wildcard is still usable.
        return bare.startsWith("addr(") || bare.startsWith("raw(");
    }

    /** Re-encodes recognized extended public keys for the configured network while preserving values that cannot be converted. */
    private String rewriteDescriptorXpubsForNetwork(String descriptor) {
        if (descriptor == null || descriptor.isBlank()) {
            return descriptor;
        }
        // Match xpub/tpub/ypub/zpub/upub/vpub base58 bodies and re-encode for this network.
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(
                "\\b([xtyzuv]pub[1-9A-HJ-NP-Za-km-z]{20,})\\b");
        java.util.regex.Matcher matcher = pattern.matcher(descriptor);
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            String raw = matcher.group(1);
            String rewritten;
            try {
                rewritten = addressDerivationService.toNetworkExtendedPublicKey(raw);
            } catch (RuntimeException ignored) {
                rewritten = raw;
            }
            matcher.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(rewritten));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    /** Returns newest-first response projections for the user's visible wallet lifecycle states. */
    @Transactional(readOnly = true)
    public List<KfeWalletResponse> listWallets(Long userId) {
        return walletRepository.findByUserIdAndStatusInOrderByCreatedAtDesc(userId, USER_VISIBLE_WALLET_STATUSES).stream()
                .map(responseMapper::toWalletResponse)
                .toList();
    }

    /** Locks the user's wallet, validates its lifecycle and label, records an audit event, and publishes dashboard refresh after commit. */
    @Transactional
    public KfeWalletResponse updateWallet(Long userId, UUID walletId, KfeUpdateWalletRequest request) {
        KfeWalletEntity wallet = walletRepository.findByIdAndUserIdForUpdate(walletId, userId)
                .orElseThrow(() -> new IllegalArgumentException("KFE wallet not found."));
        try {
            WalletLifecyclePolicy.requireUpdatable(
                    WalletStatus.valueOf(wallet.getStatus().name()),
                    request == null ? null : request.label());
        } catch (WalletRuleViolation violation) {
            if (wallet.getStatus() == KfeWalletStatus.ARCHIVED) {
                throw new IllegalStateException(violation.getMessage(), violation);
            }
            throw violation;
        }
        wallet.setLabel(request.label().trim());
        wallet = walletRepository.save(wallet);
        auditLogService.record(
                "KFE_WALLET_UPDATED",
                null,
                wallet.getId(),
                null,
                null,
                Map.of("walletId", wallet.getId().toString(), "label", wallet.getLabel()));
        dashboardPublisher.publishAfterCommit(userId);
        return responseMapper.toWalletResponse(wallet);
    }

    /** Locks and archives an eligible owner wallet, disables spendability, and treats an already archived wallet as idempotent. */
    @Transactional
    public KfeWalletResponse archiveWallet(Long userId, UUID walletId) {
        KfeWalletEntity wallet = walletRepository.findByIdAndUserIdForUpdate(walletId, userId)
                .orElseThrow(() -> new IllegalArgumentException("KFE wallet not found."));
        if (wallet.getStatus() == KfeWalletStatus.ARCHIVED) {
            return responseMapper.toWalletResponse(wallet);
        }
        try {
            WalletLifecyclePolicy.requireArchivable(WalletStatus.valueOf(wallet.getStatus().name()));
        } catch (WalletRuleViolation violation) {
            throw new IllegalStateException(violation.getMessage(), violation);
        }
        wallet.setStatus(KfeWalletStatus.ARCHIVED);
        wallet.setSpendable(false);
        wallet = walletRepository.save(wallet);
        auditLogService.record(
                "KFE_WALLET_ARCHIVED",
                null,
                wallet.getId(),
                null,
                null,
                Map.of("walletId", wallet.getId().toString()));
        dashboardPublisher.publishAfterCommit(userId);
        return responseMapper.toWalletResponse(wallet);
    }

    /** Lists the supported product wallet names with their display labels. */
    public List<KfeWalletNameOption> availableWalletNames() {
        return List.of(KfeWalletName.values()).stream()
                .map(name -> new KfeWalletNameOption(name, name.label()))
                .toList();
    }

    /**
     * Returns an active receive address for {@code walletId}, issuing one if needed.
     * Used when routing platform on-chain payments to a user's custodial/cold sink.
     */
    /** Returns an eligible existing receive/monitor address or derives and stores a fresh address under the wallet lock. */
    @Transactional
    public String ensureActiveReceiveAddress(Long userId, UUID walletId) {
        KfeWalletEntity wallet = walletRepository.findByIdAndUserIdForUpdate(walletId, userId)
                .orElseThrow(() -> new IllegalArgumentException("KFE wallet not found."));
        try {
            WalletLifecyclePolicy.requireRotatable(WalletStatus.valueOf(wallet.getStatus().name()));
        } catch (WalletRuleViolation violation) {
            throw new IllegalStateException(violation.getMessage(), violation);
        }
        if (wallet.getKind() == KfeWalletKind.WATCH_ONLY && !hasText(wallet.getXpub())
                && !receiveAddressIssuer.canIssue()) {
            throw new IllegalStateException(
                    "WATCH_ONLY wallet has no XPUB and cannot issue a receive address.");
        }
        List<KfeWalletAddressEntity> active = addressRepository.findByWalletIdAndStatusOrderByCreatedAtDesc(
                wallet.getId(), KfeWalletAddressStatus.ACTIVE);
        for (KfeWalletAddressEntity row : active) {
            if (row.getAddress() != null
                    && !row.getAddress().isBlank()
                    && (row.getAddressRole() == null
                            || row.getAddressRole() == KfeWalletAddressRole.RECEIVE
                            || row.getAddressRole() == KfeWalletAddressRole.MONITOR)) {
                return row.getAddress().trim();
            }
        }
        KfeWalletAddressEntity issued = issueFreshAddress(wallet, false);
        return issued.getAddress().trim();
    }

    /** Persists ROTATING_ADDRESS, obtains quorum outside the DB transaction, then rotates atomically or restores ACTIVE on failure. */
    public KfeAddressResponse rotateAddress(Long userId, UUID walletId) {
        PendingAddressRotation pending = Objects.requireNonNull(transactionTemplate.execute(status ->
                beginAddressRotation(userId, walletId)));
        KfeQuorumGateway.Result quorum;
        try {
            quorum = quorumGateway.requireHealthyUnanimousConsensus(pending.proposalHash());
        } catch (RuntimeException exception) {
            log.warn(
                    "[KFE Wallet] Address rotation quorum unavailable walletId={}: {}",
                    walletId,
                    exception.getMessage());
            restoreWalletStatus(userId, walletId, KfeWalletStatus.ACTIVE, "Address rotation quorum failed.");
            throw new FinancialProviderUnavailableException(
                    "KFE wallet quorum is temporarily unavailable. Address rotation was not completed.",
                    exception);
        }

        try {
            return Objects.requireNonNull(transactionTemplate.execute(status ->
                    finishAddressRotation(userId, walletId, pending.proposalHash(), quorum)));
        } catch (RuntimeException exception) {
            restoreWalletStatus(userId, walletId, KfeWalletStatus.ACTIVE, "Address rotation failed.");
            throw exception;
        }
    }

    /** Locks the owner wallet, validates watch-only key material and lifecycle eligibility, then stores the rotation-in-progress state and proposal hash. */
    private PendingAddressRotation beginAddressRotation(Long userId, UUID walletId) {
        KfeWalletEntity wallet = walletRepository.findByIdAndUserIdForUpdate(walletId, userId)
                .orElseThrow(() -> new IllegalArgumentException("KFE wallet not found."));
        if (wallet.getKind() == KfeWalletKind.WATCH_ONLY && !hasText(wallet.getXpub())) {
            throw new IllegalArgumentException(
                    "WATCH_ONLY wallets require an XPUB to issue receiving addresses.");
        }
        WalletLifecyclePolicy.requireRotatable(WalletStatus.valueOf(wallet.getStatus().name()));

        wallet.setStatus(KfeWalletStatus.ROTATING_ADDRESS);
        walletRepository.save(wallet);
        String proposalHash = hashService.sha256("KFE_WALLET_ADDRESS_ROTATE|" + userId + "|" + wallet.getId());
        dashboardPublisher.publishAfterCommit(userId);
        return new PendingAddressRotation(proposalHash);
    }

    /** Locks the wallet, retires old receive addresses, stores the new one, restores ACTIVE, and records quorum audit evidence. */
    private KfeAddressResponse finishAddressRotation(
            Long userId,
            UUID walletId,
            String proposalHash,
            KfeQuorumGateway.Result quorum) {
        KfeWalletEntity wallet = walletRepository.findByIdAndUserIdForUpdate(walletId, userId)
                .orElseThrow(() -> new IllegalArgumentException("KFE wallet not found."));
        KfeWalletAddressEntity address = issueFreshAddress(wallet, true);
        wallet.setStatus(KfeWalletStatus.ACTIVE);
        walletRepository.save(wallet);

        auditLogService.record(
                "KFE_WALLET_ADDRESS_ROTATED",
                null,
                wallet.getId(),
                null,
                null,
                Map.of(
                        "walletId", wallet.getId().toString(),
                        "addressId", address.getId().toString(),
                        "proposalHash", proposalHash,
                        "quorumAckCount", quorum.acceptedNodes()));
        dashboardPublisher.publishAfterCommit(userId);
        return responseMapper.toAddressResponse(address);
    }

    /** Rejects runtime-managed system kinds and watch-only or initial-address requests lacking required public derivation/issuer material. */
    private void validateCreateRequest(KfeCreateWalletRequest request) {
        if (request.kind() == KfeWalletKind.SYSTEM_FUNDS || request.kind() == KfeWalletKind.SYSTEM_PROFIT) {
            throw new IllegalArgumentException("System wallets are managed by KFE runtime bootstrap.");
        }
        if (request.kind() == KfeWalletKind.WATCH_ONLY
                && !hasText(request.xpub())
                && !hasText(request.descriptor())) {
            throw new IllegalArgumentException("WATCH_ONLY wallets require xpub or descriptor.");
        }
        if (Boolean.TRUE.equals(request.issueInitialAddress())
                && !hasText(request.initialAddress())
                && !hasText(request.xpub())
                && !receiveAddressIssuer.canIssue()) {
            throw new IllegalArgumentException(
                    "Issuing an initial address requires an XPUB, initial address or configured KFE receive issuer.");
        }
    }

    /** Retires active addresses when requested, then derives an xpub, shared mesh, or configured issuer address according to wallet kind. */
    private KfeWalletAddressEntity issueFreshAddress(KfeWalletEntity wallet, boolean retireExisting) {
        if (retireExisting) {
            List<KfeWalletAddressEntity> activeAddresses = addressRepository.findByWalletIdAndStatusOrderByCreatedAtDesc(
                    wallet.getId(),
                    KfeWalletAddressStatus.ACTIVE);
            activeAddresses.forEach(KfeWalletAddressEntity::retire);
            if (!activeAddresses.isEmpty()) {
                addressRepository.saveAll(activeAddresses);
            }
        }

        // USERS policy: WATCH_ONLY receiving addresses are sourced from the shared vault-mesh tb1p deposit only.
        if (wallet.getKind() == KfeWalletKind.WATCH_ONLY && !hasText(wallet.getXpub())) {
            VaultMeshSettlementPort port = vaultMeshSettlementPort.getIfAvailable();
            if (port != null) {
                VaultMeshDepositInfo dep = port.getUsersDepositAddress();
                if (dep != null && hasText(dep.address())) {
                    return saveAddress(wallet, dep.address(), "mesh-tb1p", null, "KFE_MESH_TB1P");
                }
            }
            throw new IllegalStateException(
                    "USERS deposit policy requires shared Taproot tb1p; vault-mesh deposit address unavailable.");
        }

        if (hasText(wallet.getXpub())) {
            int nextIndex = wallet.getLastDerivedIndex() + 1;
            AddressDerivationService.DerivedAddress derived =
                    addressDerivationService.deriveAddressDetailsFromXpub(wallet.getXpub(), nextIndex);
            wallet.setLastDerivedIndex(nextIndex);
            walletRepository.save(wallet);
            String accountPath = hasText(wallet.getDerivationPath())
                    ? wallet.getDerivationPath().trim()
                    : "m/84'/0'/0'";
            if ("m".equals(accountPath) || "m/".equals(accountPath)) {
                accountPath = "m";
            }
            String childPath = "m".equals(accountPath)
                    ? "m/0/" + nextIndex
                    : accountPath + "/0/" + nextIndex;
            return saveAddress(
                    wallet,
                    derived.address(),
                    childPath,
                    nextIndex,
                    "KFE_XPUB_DERIVATION");
        }

        KfeReceiveAddressIssuer.IssuedAddress issued = receiveAddressIssuer.issue(
                "kfe-wallet-" + wallet.getId());
        if (issued.derivationIndex() >= 0) {
            wallet.setLastDerivedIndex(issued.derivationIndex());
            walletRepository.save(wallet);
        }
        return saveAddress(
                wallet,
                issued.address(),
                issued.derivationPath(),
                issued.derivationIndex() >= 0 ? issued.derivationIndex() : null,
                issued.providerReference());
    }

    /** Stores a caller-supplied initial receive address with optional derivation metadata and a default provider label. */
    private KfeWalletAddressEntity createProvidedAddress(KfeWalletEntity wallet, KfeCreateWalletRequest request) {
        return saveAddress(
                wallet,
                request.initialAddress().trim(),
                blankToNull(request.initialAddressDerivationPath()),
                request.initialAddressDerivationIndex(),
                firstText(request.initialAddressProviderReference(), "CLIENT_PROVIDED"));
    }

    /** Normalizes and persists an address, reusing same-seed addresses while rejecting reuse from another active wallet. */
    private KfeWalletAddressEntity saveAddress(
            KfeWalletEntity wallet,
            String addressValue,
            String derivationPath,
            Integer derivationIndex,
            String providerReference) {
        String normalized = addressValue == null ? "" : addressValue.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("Address is required.");
        }

        // Re-import of the same seed/xpub reuses the same first receive address.
        // Unique constraint on address must not fail create after archive/KEYGEN_FAILED.
        java.util.Optional<KfeWalletAddressEntity> existing =
                addressRepository.findFirstByAddressIgnoreCase(normalized);
        if (existing.isPresent()) {
            KfeWalletAddressEntity address = existing.get();
            if (!wallet.getId().equals(address.getWalletId())) {
                KfeWalletEntity previous = walletRepository.findById(address.getWalletId()).orElse(null);
                if (previous != null && previous.getStatus() == KfeWalletStatus.ACTIVE
                        && !previous.getId().equals(wallet.getId())) {
                    throw new IllegalArgumentException(
                            "Este endereço já está vinculado a outra carteira ativa. "
                                    + "Arquive a carteira antiga antes de reimportar a mesma seed.");
                }
                address.setWalletId(wallet.getId());
            }
            address.setAddressRole(KfeWalletAddressRole.RECEIVE);
            address.setStatus(KfeWalletAddressStatus.ACTIVE);
            address.setDerivationPath(derivationPath);
            address.setDerivationIndex(derivationIndex);
            if (hasText(providerReference)) {
                address.setProviderReference(providerReference);
            }
            address.setRetiredAt(null);
            return addressRepository.save(address);
        }

        KfeWalletAddressEntity address = new KfeWalletAddressEntity();
        address.setWalletId(wallet.getId());
        address.setAddress(normalized);
        address.setAddressRole(KfeWalletAddressRole.RECEIVE);
        address.setStatus(KfeWalletAddressStatus.ACTIVE);
        address.setDerivationPath(derivationPath);
        address.setDerivationIndex(derivationIndex);
        address.setProviderReference(providerReference);
        return addressRepository.save(address);
    }

    /** Rejects wallet operations unless the persisted wallet status is ACTIVE. */
    private void requireActive(KfeWalletEntity wallet) {
        if (wallet.getStatus() != KfeWalletStatus.ACTIVE) {
            throw new IllegalStateException("Wallet is not active.");
        }
    }

    /** Computes the stable policy digest bound into wallet creation quorum proposals. */
    private String quorumPolicyHash(KfeWalletKind kind) {
        return hashService.sha256("KFE_WALLET_POLICY|kind=" + kind
                + "|quorum=healthy-unanimous-min-2|pricing=onchain-0.9pct");
    }

    /** Binds owner, pending wallet, kind, and policy digest into the wallet creation proposal. */
    private String kfeWalletCreateProposalHash(Long userId, PendingWallet wallet) {
        return hashService.sha256("KFE_WALLET_CREATE|" + userId + "|" + wallet.walletId()
                + "|" + wallet.kind() + "|" + wallet.quorumPolicyHash());
    }

    /** Uses a separate transaction to persist failure state/audit without masking the original provisioning error. */
    private void markWalletCreationFailed(Long userId, UUID walletId, KfeWalletStatus status, String reason) {
        try {
            transactionTemplate.executeWithoutResult(transactionStatus -> {
                walletRepository.findByIdAndUserIdForUpdate(walletId, userId).ifPresent(wallet -> {
                    wallet.setStatus(status);
                    walletRepository.save(wallet);
                    auditLogService.record(
                            "KFE_WALLET_CREATE_FAILED",
                            null,
                            wallet.getId(),
                            null,
                            null,
                            Map.of(
                                    "walletId", wallet.getId().toString(),
                                    "status", status.name(),
                                    "reason", safeReason(reason)));
                    dashboardPublisher.publishAfterCommit(userId);
                });
            });
        } catch (RuntimeException markerException) {
            log.warn(
                    "[KFE Wallet] Failed to persist wallet creation failure walletId={}: {}",
                    walletId,
                    markerException.getMessage());
        }
    }

    /** Uses a separate transaction to restore wallet status after failed address rotation and records the recovery event. */
    private void restoreWalletStatus(Long userId, UUID walletId, KfeWalletStatus status, String reason) {
        try {
            transactionTemplate.executeWithoutResult(transactionStatus -> {
                walletRepository.findByIdAndUserIdForUpdate(walletId, userId).ifPresent(wallet -> {
                    wallet.setStatus(status);
                    walletRepository.save(wallet);
                    auditLogService.record(
                            "KFE_WALLET_STATUS_RESTORED",
                            null,
                            wallet.getId(),
                            null,
                            null,
                            Map.of(
                                    "walletId", wallet.getId().toString(),
                                    "status", status.name(),
                                    "reason", safeReason(reason)));
                    dashboardPublisher.publishAfterCommit(userId);
                });
            });
        } catch (RuntimeException markerException) {
            log.warn(
                    "[KFE Wallet] Failed to restore wallet status walletId={}: {}",
                    walletId,
                    markerException.getMessage());
        }
    }

    /** Selects the fixed system label, explicit label, or named product label and rejects missing labels. */
    private String resolveWalletLabel(KfeCreateWalletRequest request) {
        if (request.kind() == KfeWalletKind.INTERNAL) {
            return INTERNAL_GLOBAL_WALLET_LABEL;
        }
        if (request.kind() == KfeWalletKind.SYSTEM_FUNDS) {
            return "Kerosene Fundos Globais";
        }
        if (request.kind() == KfeWalletKind.SYSTEM_PROFIT) {
            return "Kerosene Lucro";
        }
        if (hasText(request.label())) {
            return request.label().trim();
        }
        if (request.name() != null) {
            return request.name().label();
        }
        throw new IllegalArgumentException("Wallet label is required.");
    }

    /** Trims optional values and maps empty/whitespace-only strings to null. */
    private String blankToNull(String value) {
        return hasText(value) ? value.trim() : null;
    }

    /** Returns the trimmed nonblank value or the supplied fallback. */
    private String firstText(String value, String fallback) {
        return hasText(value) ? value.trim() : fallback;
    }

    /** Bounds a provisioning failure reason before storing it in wallet state or audit metadata. */
    private String safeReason(RuntimeException exception) {
        return safeReason(exception.getMessage());
    }

    /** Bounds a provisioning failure reason before storing it in wallet state or audit metadata. */
    private String safeReason(String reason) {
        String clean = hasText(reason) ? reason.trim() : "unavailable";
        return clean.length() > FAILURE_REASON_MAX_LENGTH
                ? clean.substring(0, FAILURE_REASON_MAX_LENGTH)
                : clean;
    }

    /** Tests whether a string contains any non-whitespace characters. */
    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    /** Database-created wallet facts carried across quorum and key provisioning.
     * @param walletId persisted wallet identifier
     * @param kind wallet kind whose creation policy was checked
     * @param quorumPolicyHash stable digest of the policy requiring consensus
     */
    private record PendingWallet(UUID walletId, KfeWalletKind kind, String quorumPolicyHash) {
    }

    /** Quorum proposal binding carried between the rotation state and completion transactions.
     * @param proposalHash digest submitted to the unanimous consensus gateway
     */
    private record PendingAddressRotation(String proposalHash) {
    }
}
