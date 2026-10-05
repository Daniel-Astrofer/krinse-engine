package com.kerosene.kfe.paymentexecution.adapters.out.settlement;

import com.kerosene.kfe.audit.adapters.out.persistence.KfeAuditLogService;
import com.kerosene.kfe.ledger.adapters.out.persistence.balance.KfeBalanceService;
import com.kerosene.kfe.ledger.adapters.out.persistence.settlement.KfeFeeSettlementService;
import com.kerosene.kfe.ledger.adapters.out.persistence.statement.KfeStatementService;
import com.kerosene.kfe.messaging.adapters.out.persistence.KfeFinancialNotificationOutboxService;
import com.kerosene.kfe.messaging.adapters.out.websocket.KfeDashboardPublisher;
import com.kerosene.kfe.paymentexecution.adapters.in.http.mapping.KfeResponseMapper;
import com.kerosene.kfe.pricing.adapters.in.KfePricingService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import com.kerosene.common.financial.notification.FinancialNotificationPort;
import com.kerosene.kfe.ledger.adapters.out.persistence.KfeBalanceMovementRecorder;
import com.kerosene.kfe.ledger.domain.KfeLedgerMovementTypes;
import com.kerosene.kfe.paymentexecution.adapters.out.rail.KfePlatformOnchainDestinationRouter;
import com.kerosene.kfe.bootstrap.config.bitcoin.KfeBitcoinFinalityPolicy;
import com.kerosene.kfe.adapters.out.persistence.model.shared.KfeDirection;
import com.kerosene.kfe.adapters.out.persistence.model.paymentrequest.KfePaymentRequestEntity;
import com.kerosene.kfe.adapters.out.persistence.model.paymentrequest.KfePaymentRequestStatus;
import com.kerosene.kfe.adapters.out.persistence.model.shared.KfeRail;
import com.kerosene.kfe.adapters.out.persistence.model.paymentexecution.KfeTransactionEntity;
import com.kerosene.kfe.adapters.out.persistence.model.paymentexecution.KfeTransactionStatus;
import com.kerosene.kfe.adapters.out.persistence.model.wallet.KfeWalletEntity;
import com.kerosene.kfe.adapters.out.persistence.repository.ledger.KfeBalanceMovementRepository;
import com.kerosene.kfe.adapters.out.persistence.repository.paymentrequest.KfePaymentRequestRepository;
import com.kerosene.kfe.adapters.out.persistence.repository.paymentexecution.KfeTransactionRepository;
import com.kerosene.kfe.adapters.out.persistence.repository.wallet.KfeWalletRepository;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Instantly surfaces an <b>inbound</b> history row + push for the recipient when a Kerosene
 * user sends on-chain to another platform address (custodial/cold sink).
 *
 * <p>Deposit UTXO polling alone is too slow / racy for "na hora" UX: the recipient app stayed
 * blank until a later observe cycle. This path creates the inbound from the known outbound
 * broadcast (txid + amount) and lets the custodial observer only reconcile confs later.
 */
@Service
public class KfePlatformPeerInboundService {

    /** Logger for best-effort routing, audit, and notification diagnostics. */
    private static final Logger log = LoggerFactory.getLogger(KfePlatformPeerInboundService.class);
    /** Provider marker distinguishing recipient projections created from platform peer broadcasts. */
    public static final String PROVIDER = "PLATFORM_PEER_ONCHAIN";
    /** Ledger asset credited for Bitcoin on-chain peer receipts. */
    private static final String ASSET_BTC = "BTC";

    /** Resolves a destination address to its platform-owned receive/sink wallet. */
    private final KfePlatformOnchainDestinationRouter destinationRouter;
    /** Loads destination wallet ownership for the resolved platform sink. */
    private final KfeWalletRepository walletRepository;
    /** Persists recipient-side inbound transactions and supports txid/idempotency lookups. */
    private final KfeTransactionRepository transactionRepository;
    /** Detects whether a transaction already produced a user-available ledger credit. */
    private final KfeBalanceMovementRepository movementRepository;
    /** Locks and closes matching receive requests in request-before-transaction order. */
    private final KfePaymentRequestRepository paymentRequestRepository;
    /** Applies available-balance credits after the movement idempotency fence succeeds. */
    private final KfeBalanceService balanceService;
    /** Creates idempotent ledger movement records for custodial inbound credits. */
    private final KfeBalanceMovementRecorder movementRecorder;
    /** Calculates receiver amount and platform fees for the inbound on-chain amount. */
    private final KfePricingService pricingService;
    /** Settles the platform fee once an inbound reaches the finality threshold. */
    private final KfeFeeSettlementService feeSettlementService;
    /** Persists the recipient transaction projection into the user's statement. */
    private final KfeStatementService statementService;
    /** Builds the recipient-facing statement payload for inbound transaction rows. */
    private final KfeResponseMapper responseMapper;
    /** Publishes recipient dashboard refreshes after the transaction commits. */
    private final KfeDashboardPublisher dashboardPublisher;
    /** Writes audit evidence for newly exposed inbound transactions. */
    private final KfeAuditLogService auditLogService;
    /** Optional direct notification adapter used only when the durable outbox is absent. */
    private final ObjectProvider<FinancialNotificationPort> notificationPort;
    /** Confirmation count at which an inbound row may be credited and fee-settled. */
    private final int minConfirmations;

    /**
     * Optional durable notification outbox preferred over direct best-effort delivery.
     */
    @Autowired(required = false)
    private KfeFinancialNotificationOutboxService notificationOutbox;

    /**
     * Creates the peer-inbound projection service and derives its credit finality threshold from policy.
     *
     * @param destinationRouter maps on-chain destinations to platform sink wallets
     * @param walletRepository wallet ownership lookup
     * @param transactionRepository inbound/outbound transaction persistence and lookup
     * @param movementRepository existing-credit lookup for idempotency
     * @param paymentRequestRepository locked payment-request matching and updates
     * @param balanceService available ledger balance writer
     * @param movementRecorder idempotent ledger movement recorder
     * @param pricingService inbound on-chain quote calculator
     * @param feeSettlementService platform fee ledger writer
     * @param statementService recipient statement writer
     * @param responseMapper display payload builder
     * @param dashboardPublisher post-commit dashboard refresh publisher
     * @param auditLogService audit trail writer
     * @param notificationPort optional direct notification adapter provider
     * @param finalityPolicy source of required on-chain credit confirmations
     */
    public KfePlatformPeerInboundService(
            KfePlatformOnchainDestinationRouter destinationRouter,
            KfeWalletRepository walletRepository,
            KfeTransactionRepository transactionRepository,
            KfeBalanceMovementRepository movementRepository,
            KfePaymentRequestRepository paymentRequestRepository,
            KfeBalanceService balanceService,
            KfeBalanceMovementRecorder movementRecorder,
            KfePricingService pricingService,
            KfeFeeSettlementService feeSettlementService,
            KfeStatementService statementService,
            KfeResponseMapper responseMapper,
            KfeDashboardPublisher dashboardPublisher,
            KfeAuditLogService auditLogService,
            ObjectProvider<FinancialNotificationPort> notificationPort,
            KfeBitcoinFinalityPolicy finalityPolicy) {
        this.destinationRouter = destinationRouter;
        this.walletRepository = walletRepository;
        this.transactionRepository = transactionRepository;
        this.movementRepository = movementRepository;
        this.paymentRequestRepository = paymentRequestRepository;
        this.balanceService = balanceService;
        this.movementRecorder = movementRecorder;
        this.pricingService = pricingService;
        this.feeSettlementService = feeSettlementService;
        this.statementService = statementService;
        this.responseMapper = responseMapper;
        this.dashboardPublisher = dashboardPublisher;
        this.auditLogService = auditLogService;
        this.notificationPort = notificationPort;
        this.minConfirmations = finalityPolicy.getCreditConfirmations();
    }

    /**
     * Called after a successful on-chain broadcast when destination may be a platform wallet.
     *
     * <p>{@link Propagation#REQUIRES_NEW}: must not join the caller's completed after-commit
     * synchronization (that leaves a bound EntityManager with no live TX and fails with
     * "no transaction is in progress").
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void exposeAfterOutboundBroadcast(KfeTransactionEntity outbound) {
        if (outbound == null
                || outbound.getRail() != KfeRail.ONCHAIN
                || outbound.getDirection() != KfeDirection.OUTBOUND) {
            return;
        }
        String address = outbound.getExternalReference() != null
                ? outbound.getExternalReference().trim()
                : "";
        String txid = outbound.getBlockchainTxid() != null
                ? outbound.getBlockchainTxid().trim().toLowerCase(Locale.ROOT)
                : "";
        if (address.isEmpty() || txid.isEmpty()) {
            return;
        }

        Optional<UUID> sinkWalletId = destinationRouter.findPlatformSinkWalletIdForAddress(address);
        if (sinkWalletId.isEmpty()) {
            // Address may still be platform INTERNAL that was not rewritten — resolve owner sink.
            sinkWalletId = destinationRouter.resolveRecipientOnchainSinkWalletId(address);
        }
        if (sinkWalletId.isEmpty()) {
            return;
        }

        KfeWalletEntity sink = walletRepository.findById(sinkWalletId.get()).orElse(null);
        if (sink == null || sink.getUserId() == null) {
            return;
        }
        // Do not mirror sender's own wallet as inbound for self-sends.
        if (outbound.getUserId() != null && outbound.getUserId().equals(sink.getUserId())
                && outbound.getSourceWalletId() != null
                && outbound.getSourceWalletId().equals(sink.getId())) {
            return;
        }

        long amountSats = Math.max(0L, outbound.getReceiverAmountSats());
        if (amountSats <= 0L) {
            amountSats = Math.max(0L, outbound.getGrossAmountSats());
        }
        if (amountSats <= 0L) {
            return;
        }

        // Lock matching requests before touching inbound rows or balances, matching
        // cancellation's request -> transaction order. A cancelled request is excluded.
        List<KfePaymentRequestEntity> openRequests = paymentRequestRepository.findOpenByAddressAndRailForUpdate(
                address, KfePaymentRequestStatus.OPEN, KfeRail.ONCHAIN, sink.getUserId());

        // Already have inbound for this chain tx on this user/wallet?
        List<KfeTransactionEntity> existing =
                transactionRepository.findByBlockchainTxidAndUserId(txid, sink.getUserId());
        for (KfeTransactionEntity row : existing) {
            if (row.getDirection() == KfeDirection.INBOUND
                    && sink.getId().equals(row.getDestinationWalletId())) {
                refreshExisting(row, sink, amountSats, outbound.getConfirmations());
                // Still close any open QR/payment-request for this address (recipient UI polls that).
                closeMatchingPaymentRequests(openRequests, address, amountSats, row);
                return;
            }
        }

        String idempotencyKey = "platform-peer-in:" + outbound.getId();
        if (transactionRepository.findByIdempotencyKey(idempotencyKey).isPresent()) {
            return;
        }

        KfePricingService.Quote quote;
        try {
            quote = pricingService.quote(KfeRail.ONCHAIN, KfeDirection.INBOUND, amountSats, 0L);
        } catch (RuntimeException exception) {
            log.warn(
                    "[KFE Peer Inbound] quote failed sink={} amount={}: {}",
                    sink.getId(),
                    amountSats,
                    exception.getMessage());
            return;
        }

        int confs = Math.max(0, outbound.getConfirmations());
        boolean settleNow = confs >= minConfirmations;
        KfeTransactionStatus status =
                settleNow ? KfeTransactionStatus.SETTLED : KfeTransactionStatus.VALIDATING;

        KfeTransactionEntity inbound = new KfeTransactionEntity();
        inbound.setUserId(sink.getUserId());
        inbound.setIdempotencyKey(idempotencyKey);
        inbound.setRail(KfeRail.ONCHAIN);
        inbound.setDirection(KfeDirection.INBOUND);
        inbound.setDestinationWalletId(sink.getId());
        inbound.setSourceWalletId(outbound.getSourceWalletId());
        inbound.setExternalReference(address);
        inbound.setMemo("Recebido de usuário Kerosene (on-chain)");
        inbound.setGrossAmountSats(quote.grossAmountSats());
        inbound.setReceiverAmountSats(quote.receiverAmountSats());
        inbound.setNetworkFeeSats(0L);
        inbound.setKeroseneFeeSats(quote.keroseneFeeSats());
        inbound.setTotalDebitSats(0L);
        inbound.setProvider(PROVIDER);
        inbound.setProviderReference(outbound.getId().toString());
        inbound.setBlockchainTxid(txid);
        inbound.setConfirmations(confs);
        inbound.setStatus(status);
        inbound = transactionRepository.saveAndFlush(inbound);

        if (settleNow) {
            if (!alreadyCredited(inbound.getId())) {
                creditOnce(inbound.getId(), sink.getId(), quote.receiverAmountSats());
            }
            feeSettlementService.creditKeroseneFee(inbound);
            notifyConfirmed(sink, inbound, quote.receiverAmountSats(), confs);
        } else {
            notifyDetected(sink, inbound, quote.receiverAmountSats(), confs);
        }

        statementService.recordUserStatement(
                sink.getUserId(),
                sink.getId(),
                inbound,
                new LinkedHashMap<>(responseMapper.buildDisplayPayload(inbound, sink.getUserId())));
        dashboardPublisher.publishAfterCommit(sink.getUserId());

        try {
            // Reuse registered custodial audit types (unknown types abort the whole expose).
            auditLogService.record(
                    settleNow ? "KFE_INBOUND_SETTLED" : "KFE_INBOUND_CREDITED",
                    inbound.getId(),
                    sink.getId(),
                    null,
                    status,
                    Map.of(
                            "outboundTxId", outbound.getId().toString(),
                            "txid", txid,
                            "amountSats", quote.receiverAmountSats(),
                            "confirmations", confs,
                            "source", PROVIDER));
        } catch (RuntimeException auditFailure) {
            log.warn(
                    "[KFE Peer Inbound] audit skipped inboundId={}: {}",
                    inbound.getId(),
                    auditFailure.getMessage());
        }

        // Close open receive QR / payment-request so the recipient screen leaves "Pendente".
        closeMatchingPaymentRequests(openRequests, address, amountSats, inbound);

        log.info(
                "[KFE Peer Inbound] exposed recipientUserId={} sinkWalletId={} outboundId={} txid={} amount={} status={}",
                sink.getUserId(),
                sink.getId(),
                outbound.getId(),
                txid,
                quote.receiverAmountSats(),
                status);
    }

    /**
     * Marks OPEN on-chain payment requests for {@code address} as PAID when a peer inbound lands.
     * Without this, funds credit via PLATFORM_PEER_ONCHAIN but the QR screen keeps polling OPEN
     * until the slower chain UTXO monitor catches up (often minutes / never in fee-edge cases).
     */
    private void closeMatchingPaymentRequests(
            List<KfePaymentRequestEntity> openRequests,
            String address, long observedSats, KfeTransactionEntity inbound) {
        if (address == null || address.isBlank() || inbound == null || inbound.getId() == null) {
            return;
        }
        if (openRequests.isEmpty()) {
            return;
        }
        KfePaymentRequestEntity selected = openRequests.stream()
                .filter(request -> matchesPaymentRequest(request, inbound, observedSats))
                .min((left, right) -> Integer.compare(paymentRequestMatchRank(left, observedSats),
                        paymentRequestMatchRank(right, observedSats)))
                .orElse(null);
        if (selected != null) {
            selected.markPaid(inbound.getId());
            paymentRequestRepository.save(selected);
            log.info(
                    "[KFE Peer Inbound] closed payment request publicId={} address={} inboundId={} observedSats={}",
                    selected.getPublicId(),
                    address,
                    inbound.getId(),
                    observedSats);
        }
        if (inbound.getUserId() != null) {
            dashboardPublisher.publishAfterCommit(inbound.getUserId());
        }
    }

    /**
     * Tests whether an open request belongs to the inbound recipient and amount. Open-amount requests
     * match any positive observation; fixed requests match full payment or permit the existing fee-edge
     * tolerance of max(1% of requested, 500 sats) shortfall.
     *
     * @param request candidate open payment request
     * @param inbound recipient-side transaction projection
     * @param observedSats amount observed from the outbound broadcast in satoshis
     * @return true when identity and amount rules allow this request to be marked paid
     */
    private boolean matchesPaymentRequest(
            KfePaymentRequestEntity request, KfeTransactionEntity inbound, long observedSats) {
        if (request.getStatus() != KfePaymentRequestStatus.OPEN) {
            return false;
        }
        if (request.getUserId() != null && inbound.getUserId() != null
                && !request.getUserId().equals(inbound.getUserId())) {
            return false;
        }
        Long requested = request.getAmountSats();
        if (requested == null || requested <= 0L || observedSats >= requested) {
            return true;
        }
        // Peer transfers can lose the platform fee before the receive-side row is created.
        long floor = requested - Math.max(requested / 100L, 500L);
        return observedSats >= Math.max(0L, floor);
    }

    /**
     * Ranks matching payment requests so exact amount requests precede tolerated or open-amount matches.
     *
     * @param request candidate request already passing {@link #matchesPaymentRequest}
     * @param observedSats observed inbound amount in satoshis
     * @return rank 0 for exact fixed amount, 1 for tolerated fixed amount, 2 for open amount
     */
    private int paymentRequestMatchRank(KfePaymentRequestEntity request, long observedSats) {
        Long requested = request.getAmountSats();
        if (requested == null || requested <= 0L) return 2;
        return requested == observedSats ? 0 : 1;
    }

    /**
     * Advances a previously projected inbound's confirmations/amount and settles it once the finality
     * threshold is reached. Credit, fee settlement, notification, statement, and dashboard work occur
     * only when the stored transaction changes and movement idempotency permits the credit.
     *
     * @param existing recipient-side inbound transaction
     * @param sink destination wallet owning the inbound
     * @param amountSats latest observed amount in satoshis
     * @param confs latest outbound confirmation count
     */
    private void refreshExisting(
            KfeTransactionEntity existing, KfeWalletEntity sink, long amountSats, int confs) {
        boolean changed = false;
        if (confs > existing.getConfirmations()) {
            existing.setConfirmations(confs);
            changed = true;
        }
        if (amountSats > existing.getGrossAmountSats()) {
            existing.setGrossAmountSats(amountSats);
            existing.setReceiverAmountSats(Math.max(existing.getReceiverAmountSats(), amountSats));
            changed = true;
        }
        boolean canSettle = existing.getConfirmations() >= minConfirmations;
        if (canSettle && existing.getStatus() != KfeTransactionStatus.SETTLED) {
            existing.setStatus(KfeTransactionStatus.SETTLED);
            changed = true;
            if (!alreadyCredited(existing.getId())) {
                long credit = Math.max(existing.getReceiverAmountSats(), existing.getGrossAmountSats());
                if (credit > 0L && creditOnce(existing.getId(), sink.getId(), credit)) {
                    feeSettlementService.creditKeroseneFee(existing);
                    notifyConfirmed(sink, existing, credit, existing.getConfirmations());
                }
            }
        }
        if (changed) {
            transactionRepository.save(existing);
            statementService.recordUserStatement(
                    sink.getUserId(),
                    sink.getId(),
                    existing,
                    new LinkedHashMap<>(responseMapper.buildDisplayPayload(existing, sink.getUserId())));
            dashboardPublisher.publishAfterCommit(sink.getUserId());
        }
    }

    /**
     * Checks existing user-available credit movement types for the inbound transaction.
     *
     * @param transactionId transaction whose credit history is checked
     * @return true when a qualifying ledger credit already exists
     */
    private boolean alreadyCredited(UUID transactionId) {
        return movementRepository.existsByTransactionIdAndMovementTypeIn(
                transactionId, KfeLedgerMovementTypes.USER_AVAILABLE_CREDIT_TYPES);
    }

    /**
     * Records the unique available credit movement before mutating the wallet balance.
     *
     * @param transactionId inbound transaction idempotency scope
     * @param walletId recipient wallet to credit
     * @param creditSats amount to credit in satoshis
     * @return true only when this call created the movement and applied its balance effect
     */
    private boolean creditOnce(UUID transactionId, UUID walletId, long creditSats) {
        boolean wrote = movementRecorder.record(
                transactionId,
                walletId,
                KfeLedgerMovementTypes.CREDIT_CUSTODIAL_DEPOSIT,
                creditSats,
                null,
                "AVAILABLE");
        if (!wrote) {
            return false;
        }
        balanceService.creditAvailable(walletId, ASSET_BTC, creditSats);
        return true;
    }

    /**
     * Emits the initial on-chain deposit-detected notification through the durable outbox when present,
     * otherwise uses the optional direct port and treats delivery errors as nonfatal.
     *
     * @param wallet recipient sink wallet
     * @param tx inbound transaction projection
     * @param amount receiver amount in satoshis
     * @param confs observed confirmations
     */
    private void notifyDetected(KfeWalletEntity wallet, KfeTransactionEntity tx, long amount, int confs) {
        if (notificationOutbox != null) {
            notificationOutbox.enqueue(
                    notificationOutbox.stableEventId("DEPOSIT_DETECTED", tx.getId(), tx.getBlockchainTxid()),
                    wallet.getUserId(), tx.getId(), "DEPOSIT_DETECTED",
                    Map.of("walletId", wallet.getId(), "rail", "ONCHAIN", "amountSats", amount,
                            "confirmations", confs, "txid", tx.getBlockchainTxid()));
            return;
        }
        FinancialNotificationPort port = notificationPort.getIfAvailable();
        if (port == null) {
            return;
        }
        try {
            port.notifyDepositDetected(
                    wallet.getUserId(), tx.getId(), wallet.getId(), "ONCHAIN", amount, confs);
        } catch (RuntimeException exception) {
            log.warn("[KFE Peer Inbound] notify detected failed: {}", exception.getMessage());
        }
    }

    /**
     * Emits the finality-confirmed deposit notification through the outbox or optional direct fallback.
     *
     * @param wallet recipient sink wallet
     * @param tx settled inbound transaction
     * @param amount receiver amount in satoshis
     * @param confs final confirmation count observed
     */
    private void notifyConfirmed(KfeWalletEntity wallet, KfeTransactionEntity tx, long amount, int confs) {
        if (notificationOutbox != null) {
            notificationOutbox.enqueue(
                    notificationOutbox.stableEventId("DEPOSIT_CONFIRMED", tx.getId(), "settled"),
                    wallet.getUserId(), tx.getId(), "DEPOSIT_CONFIRMED",
                    Map.of("walletId", wallet.getId(), "rail", "ONCHAIN", "amountSats", amount,
                            "confirmations", confs, "txid", tx.getBlockchainTxid()));
            return;
        }
        FinancialNotificationPort port = notificationPort.getIfAvailable();
        if (port == null) {
            return;
        }
        try {
            port.notifyDepositConfirmed(
                    wallet.getUserId(), tx.getId(), wallet.getId(), "ONCHAIN", amount, confs);
        } catch (RuntimeException exception) {
            log.warn("[KFE Peer Inbound] notify confirmed failed: {}", exception.getMessage());
        }
    }
}
