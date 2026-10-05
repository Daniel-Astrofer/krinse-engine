package com.kerosene.kfe.adapters.out.persistence.model.paymentexecution;

import com.kerosene.kfe.adapters.out.persistence.model.shared.KfeDirection;
import com.kerosene.kfe.adapters.out.persistence.model.shared.KfeRail;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * JPA representation of a payment transaction, including its idempotency, settlement,
 * pricing, provider and confirmation-monitoring state.
 *
 * <p>The unique user/idempotency constraint prevents duplicate transaction intents. Lifecycle
 * callbacks store UTC timestamps, and the indexes support user history, status and provider
 * reconciliation queries.</p>
 */
@Entity
@Table(name = "transactions_master", schema = "financial",
        uniqueConstraints = @UniqueConstraint(name = "unique_user_idempotency", columnNames = {"user_id", "idempotency_key"}), indexes = {
        @Index(name = "idx_transactions_master_user_created", columnList = "user_id, created_at"),
        @Index(name = "idx_transactions_master_status", columnList = "status"),
        @Index(name = "idx_transactions_master_provider_reference", columnList = "provider_reference, status")
})
public class KfeTransactionEntity {

    /**
     * Stable transaction identifier generated before persistence.
     */
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id = UUID.randomUUID();

    /**
     * Client key that makes creation retries for the same user idempotent.
     */
    @Column(name = "idempotency_key", nullable = false, length = 180)
    private String idempotencyKey;

    /**
     * Identifier of the user who owns this transaction.
     */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    /**
     * Optional internal source wallet identifier.
     */
    @Column(name = "source_wallet_id")
    private UUID sourceWalletId;

    /**
     * Optional internal destination wallet identifier.
     */
    @Column(name = "destination_wallet_id")
    private UUID destinationWalletId;

    /**
     * Reference supplied by an external payment or reconciliation system.
     */
    @Column(name = "external_reference", columnDefinition = "TEXT")
    private String externalReference;

    /**
     * User-facing transaction memo, when supplied.
     */
    @Column(name = "memo", length = 255)
    private String memo;

    /**
     * Payment rail used to execute the transfer.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "rail", nullable = false, length = 32)
    private KfeRail rail;

    /**
     * Whether funds move into or out of the user's account.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "direction", nullable = false, length = 32)
    private KfeDirection direction;

    /**
     * Aggregate transaction lifecycle state, initially an intent.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private KfeTransactionStatus status = KfeTransactionStatus.INTENT;

    /**
     * Total amount requested before fees, in satoshis.
     */
    @Column(name = "gross_amount_sats", nullable = false)
    private long grossAmountSats;

    /**
     * Amount expected to reach the recipient, in satoshis.
     */
    @Column(name = "receiver_amount_sats", nullable = false)
    private long receiverAmountSats;

    /**
     * Network fee charged for execution, in satoshis.
     */
    @Column(name = "network_fee_sats", nullable = false)
    private long networkFeeSats;

    /**
     * Platform fee charged for execution, in satoshis.
     */
    @Column(name = "kerosene_fee_sats", nullable = false)
    private long keroseneFeeSats;

    /**
     * Total amount debited from the source, in satoshis.
     */
    @Column(name = "total_debit_sats", nullable = false)
    private long totalDebitSats;

    /**
     * Version of the fee policy used to calculate the stored quote.
     */
    @Column(name = "pricing_policy_version", nullable = false)
    private int pricingPolicyVersion;

    /**
     * BTC/USD rate captured for display when the transaction was priced.
     */
    @Column(name = "display_btc_usd", precision = 19, scale = 8)
    private BigDecimal displayBtcUsd;

    /**
     * BTC/EUR rate captured for display when the transaction was priced.
     */
    @Column(name = "display_btc_eur", precision = 19, scale = 8)
    private BigDecimal displayBtcEur;

    /**
     * BTC/BRL rate captured for display when the transaction was priced.
     */
    @Column(name = "display_btc_brl", precision = 19, scale = 8)
    private BigDecimal displayBtcBrl;

    /**
     * Fiat display value in USD captured at pricing time.
     */
    @Column(name = "display_amount_usd", precision = 19, scale = 2)
    private BigDecimal displayAmountUsd;

    /**
     * Fiat display value in EUR captured at pricing time.
     */
    @Column(name = "display_amount_eur", precision = 19, scale = 2)
    private BigDecimal displayAmountEur;

    /**
     * Fiat display value in BRL captured at pricing time.
     */
    @Column(name = "display_amount_brl", precision = 19, scale = 2)
    private BigDecimal displayAmountBrl;

    /**
     * Hash of the quorum proposal authorizing transaction execution.
     */
    @Column(name = "quorum_proposal_hash", length = 64)
    private String quorumProposalHash;

    /**
     * Number of quorum acknowledgements recorded for the proposal.
     */
    @Column(name = "quorum_ack_count", nullable = false)
    private int quorumAckCount;

    /**
     * External provider responsible for execution on the selected rail.
     */
    @Column(name = "provider", length = 64)
    private String provider;

    /**
     * Provider-side identifier used for status lookup and reconciliation.
     */
    @Column(name = "provider_reference", length = 255)
    private String providerReference;

    /**
     * On-chain transaction identifier, when execution produces one.
     */
    @Column(name = "blockchain_txid", length = 128)
    private String blockchainTxid;

    /**
     * Lightning payment hash, when the transaction uses that rail.
     */
    @Column(name = "payment_hash", length = 128)
    private String paymentHash;

    /**
     * Latest observed on-chain confirmation count.
     */
    @Column(name = "confirmations", nullable = false)
    private int confirmations;

    /**
     * Business-level status maintained separately from provider/network status.
     */
    @Column(name = "business_status", length = 32)
    private String businessStatus;

    /**
     * Latest status reported by the underlying network or rail.
     */
    @Column(name = "network_status", length = 32)
    private String networkStatus;

    /**
     * Accounting/posting status for this transaction.
     */
    @Column(name = "accounting_status", length = 32)
    private String accountingStatus;

    /**
     * Stable machine-readable failure reason, when execution fails.
     */
    @Column(name = "failure_code", length = 64)
    private String failureCode;

    /**
     * Diagnostic failure detail associated with {@link #failureCode}.
     */
    @Column(name = "failure_message", length = 255)
    private String failureMessage;

    // ITEM 8: Network tracking for disappeared transactions
    /**
     * First UTC observation of the transaction on the network.
     */
    @Column(name = "network_first_seen_at")
    private LocalDateTime networkFirstSeenAt;

    /**
     * Most recent UTC observation of the transaction on the network.
     */
    @Column(name = "network_last_seen_at")
    private LocalDateTime networkLastSeenAt;

    /**
     * Start time of the current consecutive not-found observation window.
     */
    @Column(name = "network_not_found_since")
    private LocalDateTime networkNotFoundSince;

    /**
     * Number of consecutive network probes that did not find the transaction.
     */
    @Column(name = "network_not_found_count")
    private int networkNotFoundCount;

    /**
     * Most recent time the transaction was observed in a mempool.
     */
    @Column(name = "mempool_last_seen_at")
    private LocalDateTime mempoolLastSeenAt;

    /**
     * Time of the latest authoritative chain lookup.
     */
    @Column(name = "last_chain_probe_at")
    private LocalDateTime lastChainProbeAt;

    /**
     * Result status of the latest chain lookup.
     */
    @Column(name = "last_chain_probe_status", length = 32)
    private String lastChainProbeStatus;

    // ITEM 14: Store raw tx hash at preparation for settlement verification
    /**
     * Hash of the raw transaction captured during preparation for settlement checks.
     */
    @Column(name = "prepared_raw_tx_hash", length = 64)
    private String preparedRawTxHash;

    // ITEM 8: Whether confirmation monitoring is active (for disappeared tx detection)
    /**
     * Whether background confirmation and disappearance monitoring is enabled.
     */
    @Column(name = "confirmation_monitoring_active", nullable = false)
    private boolean confirmationMonitoringActive = true;

    // ITEM 4: Track when conflicted state was entered
    /**
     * Time the transaction entered the conflicted state.
     */
    @Column(name = "conflicted_at")
    private LocalDateTime conflictedAt;

    /**
     * Transaction identifier that replaced this transaction, if any.
     */
    @Column(name = "replacement_txid", length = 128)
    private String replacementTxid;

    /**
     * UTC creation timestamp assigned by {@link #onCreate()}.
     */
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /**
     * UTC timestamp of the most recent persisted update.
     */
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /** Initializes both lifecycle timestamps using UTC before the row is inserted. */
    @PrePersist
    void onCreate() {
        // Always UTC wall clock — pods run Etc/UTC; never rely on host local TZ.
        LocalDateTime now = LocalDateTime.now(java.time.ZoneOffset.UTC);
        createdAt = now;
        updatedAt = now;
    }

    /** Refreshes the update timestamp using UTC before the row is updated. */
    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now(java.time.ZoneOffset.UTC);
    }

    /** Returns the stable database identifier. */
    public UUID getId() {
        return id;
    }

    /** Returns the idempotency key used to deduplicate creation requests. */
    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    /** Sets the idempotency key for this transaction intent.
     * @param idempotencyKey caller-provided key scoped to the owning user
     */
    public void setIdempotencyKey(String idempotencyKey) {
        this.idempotencyKey = idempotencyKey;
    }

    /** Returns the owning user identifier. */
    public Long getUserId() {
        return userId;
    }

    /** Sets the owner of this transaction.
     * @param userId user identifier
     */
    public void setUserId(Long userId) {
        this.userId = userId;
    }

    /** Returns the internal source wallet identifier, if present. */
    public UUID getSourceWalletId() {
        return sourceWalletId;
    }

    /** Sets the internal source wallet identifier.
     * @param sourceWalletId source wallet UUID, or {@code null} for an external source
     */
    public void setSourceWalletId(UUID sourceWalletId) {
        this.sourceWalletId = sourceWalletId;
    }

    /** Returns the internal destination wallet identifier, if present. */
    public UUID getDestinationWalletId() {
        return destinationWalletId;
    }

    /** Sets the internal destination wallet identifier.
     * @param destinationWalletId destination wallet UUID, or {@code null} for an external destination
     */
    public void setDestinationWalletId(UUID destinationWalletId) {
        this.destinationWalletId = destinationWalletId;
    }

    /** Returns the external payment or reconciliation reference. */
    public String getExternalReference() {
        return externalReference;
    }

    /** Sets the external payment or reconciliation reference.
     * @param externalReference provider reference, or {@code null} when unavailable
     */
    public void setExternalReference(String externalReference) {
        this.externalReference = externalReference;
    }

    /** Returns the optional user-facing memo. */
    public String getMemo() {
        return memo;
    }

    /** Sets the optional user-facing memo.
     * @param memo memo text, or {@code null} when omitted
     */
    public void setMemo(String memo) {
        this.memo = memo;
    }

    /** Returns the payment rail assigned to the transaction. */
    public KfeRail getRail() {
        return rail;
    }

    /** Sets the rail that will execute the transaction.
     * @param rail selected payment rail
     */
    public void setRail(KfeRail rail) {
        this.rail = rail;
    }

    /** Returns the transaction's funds-flow direction. */
    public KfeDirection getDirection() {
        return direction;
    }

    /** Sets whether this transaction is inbound or outbound.
     * @param direction transaction direction
     */
    public void setDirection(KfeDirection direction) {
        this.direction = direction;
    }

    /** Returns the aggregate lifecycle status. */
    public KfeTransactionStatus getStatus() {
        return status;
    }

    /** Updates the aggregate lifecycle status.
     * @param status new transaction status
     */
    public void setStatus(KfeTransactionStatus status) {
        this.status = status;
    }

    /** Returns the requested pre-fee amount in satoshis. */
    public long getGrossAmountSats() {
        return grossAmountSats;
    }

    /** Sets the requested pre-fee amount.
     * @param grossAmountSats gross amount in satoshis
     */
    public void setGrossAmountSats(long grossAmountSats) {
        this.grossAmountSats = grossAmountSats;
    }

    /** Returns the expected recipient amount in satoshis. */
    public long getReceiverAmountSats() {
        return receiverAmountSats;
    }

    /** Sets the expected recipient amount.
     * @param receiverAmountSats recipient amount in satoshis
     */
    public void setReceiverAmountSats(long receiverAmountSats) {
        this.receiverAmountSats = receiverAmountSats;
    }

    /** Returns the estimated or charged network fee in satoshis. */
    public long getNetworkFeeSats() {
        return networkFeeSats;
    }

    /** Sets the network fee amount.
     * @param networkFeeSats fee in satoshis
     */
    public void setNetworkFeeSats(long networkFeeSats) {
        this.networkFeeSats = networkFeeSats;
    }

    /** Returns the platform fee in satoshis. */
    public long getKeroseneFeeSats() {
        return keroseneFeeSats;
    }

    /** Sets the platform fee amount.
     * @param keroseneFeeSats fee in satoshis
     */
    public void setKeroseneFeeSats(long keroseneFeeSats) {
        this.keroseneFeeSats = keroseneFeeSats;
    }

    /** Returns the total amount debited from the source in satoshis. */
    public long getTotalDebitSats() {
        return totalDebitSats;
    }

    /** Sets the total source debit.
     * @param totalDebitSats total debit in satoshis
     */
    public void setTotalDebitSats(long totalDebitSats) {
        this.totalDebitSats = totalDebitSats;
    }

    /** Returns the version of the pricing policy used for this transaction. */
    public int getPricingPolicyVersion() {
        return pricingPolicyVersion;
    }

    /** Records the pricing policy version used to create the quote.
     * @param pricingPolicyVersion pricing policy version
     */
    public void setPricingPolicyVersion(int pricingPolicyVersion) {
        this.pricingPolicyVersion = pricingPolicyVersion;
    }

    /** Returns the BTC/USD display rate captured at pricing time. */
    public BigDecimal getDisplayBtcUsd() {
        return displayBtcUsd;
    }

    /** Sets the BTC/USD display rate snapshot.
     * @param displayBtcUsd captured conversion rate, or {@code null} if unavailable
     */
    public void setDisplayBtcUsd(BigDecimal displayBtcUsd) {
        this.displayBtcUsd = displayBtcUsd;
    }

    /** Returns the BTC/EUR display rate captured at pricing time. */
    public BigDecimal getDisplayBtcEur() {
        return displayBtcEur;
    }

    /** Sets the BTC/EUR display rate snapshot.
     * @param displayBtcEur captured conversion rate, or {@code null} if unavailable
     */
    public void setDisplayBtcEur(BigDecimal displayBtcEur) {
        this.displayBtcEur = displayBtcEur;
    }

    /** Returns the BTC/BRL display rate captured at pricing time. */
    public BigDecimal getDisplayBtcBrl() {
        return displayBtcBrl;
    }

    /** Sets the BTC/BRL display rate snapshot.
     * @param displayBtcBrl captured conversion rate, or {@code null} if unavailable
     */
    public void setDisplayBtcBrl(BigDecimal displayBtcBrl) {
        this.displayBtcBrl = displayBtcBrl;
    }

    /** Returns the USD display amount captured at pricing time. */
    public BigDecimal getDisplayAmountUsd() {
        return displayAmountUsd;
    }

    /** Sets the USD display amount snapshot.
     * @param displayAmountUsd fiat amount, or {@code null} if unavailable
     */
    public void setDisplayAmountUsd(BigDecimal displayAmountUsd) {
        this.displayAmountUsd = displayAmountUsd;
    }

    /** Returns the EUR display amount captured at pricing time. */
    public BigDecimal getDisplayAmountEur() {
        return displayAmountEur;
    }

    /** Sets the EUR display amount snapshot.
     * @param displayAmountEur fiat amount, or {@code null} if unavailable
     */
    public void setDisplayAmountEur(BigDecimal displayAmountEur) {
        this.displayAmountEur = displayAmountEur;
    }

    /** Returns the BRL display amount captured at pricing time. */
    public BigDecimal getDisplayAmountBrl() {
        return displayAmountBrl;
    }

    /** Sets the BRL display amount snapshot.
     * @param displayAmountBrl fiat amount, or {@code null} if unavailable
     */
    public void setDisplayAmountBrl(BigDecimal displayAmountBrl) {
        this.displayAmountBrl = displayAmountBrl;
    }

    /** Returns the quorum proposal hash associated with this transaction. */
    public String getQuorumProposalHash() {
        return quorumProposalHash;
    }

    /** Associates this transaction with a quorum proposal.
     * @param quorumProposalHash proposal hash, or {@code null} when quorum is not used
     */
    public void setQuorumProposalHash(String quorumProposalHash) {
        this.quorumProposalHash = quorumProposalHash;
    }

    /** Returns the number of acknowledgements collected for the quorum proposal. */
    public int getQuorumAckCount() {
        return quorumAckCount;
    }

    /** Records the number of collected quorum acknowledgements.
     * @param quorumAckCount acknowledgement count
     */
    public void setQuorumAckCount(int quorumAckCount) {
        this.quorumAckCount = quorumAckCount;
    }

    /** Returns the execution provider name. */
    public String getProvider() {
        return provider;
    }

    /** Sets the external execution provider.
     * @param provider provider name, or {@code null} before provider selection
     */
    public void setProvider(String provider) {
        this.provider = provider;
    }

    /** Returns the provider-side reference used for lookup and reconciliation. */
    public String getProviderReference() {
        return providerReference;
    }

    /** Sets the provider-side transaction reference.
     * @param providerReference provider reference, or {@code null} until assigned
     */
    public void setProviderReference(String providerReference) {
        this.providerReference = providerReference;
    }

    /** Returns the on-chain transaction identifier, when one exists. */
    public String getBlockchainTxid() {
        return blockchainTxid;
    }

    /** Records the on-chain transaction identifier.
     * @param blockchainTxid transaction ID, or {@code null} for non-chain execution
     */
    public void setBlockchainTxid(String blockchainTxid) {
        this.blockchainTxid = blockchainTxid;
    }

    /** Returns the Lightning payment hash, when one exists. */
    public String getPaymentHash() {
        return paymentHash;
    }

    /** Records the Lightning payment hash.
     * @param paymentHash payment hash, or {@code null} for other rails
     */
    public void setPaymentHash(String paymentHash) {
        this.paymentHash = paymentHash;
    }

    /** Returns the latest observed on-chain confirmation count. */
    public int getConfirmations() {
        return confirmations;
    }

    /** Updates the observed on-chain confirmation count.
     * @param confirmations number of confirmations reported by the chain observer
     */
    public void setConfirmations(int confirmations) {
        this.confirmations = confirmations;
    }

    /** Returns the business-level lifecycle status. */
    public String getBusinessStatus() {
        return businessStatus;
    }

    /** Sets the business-level status independently of network status.
     * @param businessStatus business status label
     */
    public void setBusinessStatus(String businessStatus) {
        this.businessStatus = businessStatus;
    }

    /** Returns the latest status observed from the payment network. */
    public String getNetworkStatus() {
        return networkStatus;
    }

    /** Sets the latest payment-network status.
     * @param networkStatus network status label
     */
    public void setNetworkStatus(String networkStatus) {
        this.networkStatus = networkStatus;
    }

    /** Returns the accounting or ledger posting status. */
    public String getAccountingStatus() {
        return accountingStatus;
    }

    /** Sets the accounting or ledger posting status.
     * @param accountingStatus accounting status label
     */
    public void setAccountingStatus(String accountingStatus) {
        this.accountingStatus = accountingStatus;
    }

    /** Returns the machine-readable failure code, if execution failed. */
    public String getFailureCode() {
        return failureCode;
    }

    /** Sets the stable machine-readable failure reason.
     * @param failureCode failure code, or {@code null} while no failure is recorded
     */
    public void setFailureCode(String failureCode) {
        this.failureCode = failureCode;
    }

    /** Returns diagnostic detail for a recorded failure. */
    public String getFailureMessage() {
        return failureMessage;
    }

    /** Sets diagnostic detail for a recorded failure.
     * @param failureMessage failure detail, or {@code null} when not applicable
     */
    public void setFailureMessage(String failureMessage) {
        this.failureMessage = failureMessage;
    }

    /** Returns the first UTC time the transaction was observed on the network. */
    public LocalDateTime getNetworkFirstSeenAt() { return networkFirstSeenAt; }
    /** Sets the first network observation time.
     * @param value observation time in UTC
     */
    public void setNetworkFirstSeenAt(LocalDateTime value) { this.networkFirstSeenAt = value; }
    /** Returns the latest UTC network observation time. */
    public LocalDateTime getNetworkLastSeenAt() { return networkLastSeenAt; }
    /** Sets the latest network observation time.
     * @param value observation time in UTC
     */
    public void setNetworkLastSeenAt(LocalDateTime value) { this.networkLastSeenAt = value; }
    /** Returns when the current not-found observation window began. */
    public LocalDateTime getNetworkNotFoundSince() { return networkNotFoundSince; }
    /** Sets the start of the current not-found window.
     * @param value UTC start time, or {@code null} when the transaction is found again
     */
    public void setNetworkNotFoundSince(LocalDateTime value) { this.networkNotFoundSince = value; }
    /** Returns the consecutive network-probe misses. */
    public int getNetworkNotFoundCount() { return networkNotFoundCount; }
    /** Records the consecutive network-probe misses.
     * @param value non-negative miss count
     */
    public void setNetworkNotFoundCount(int value) { this.networkNotFoundCount = value; }
    /** Returns the latest time the transaction was seen in a mempool. */
    public LocalDateTime getMempoolLastSeenAt() { return mempoolLastSeenAt; }
    /** Records a mempool observation time.
     * @param value observation time in UTC
     */
    public void setMempoolLastSeenAt(LocalDateTime value) { this.mempoolLastSeenAt = value; }
    /** Returns when the chain was last probed for this transaction. */
    public LocalDateTime getLastChainProbeAt() { return lastChainProbeAt; }
    /** Records the time of the latest chain probe.
     * @param value probe time in UTC
     */
    public void setLastChainProbeAt(LocalDateTime value) { this.lastChainProbeAt = value; }
    /** Returns the result of the latest chain probe. */
    public String getLastChainProbeStatus() { return lastChainProbeStatus; }
    /** Records the latest chain-probe result.
     * @param value probe result label
     */
    public void setLastChainProbeStatus(String value) { this.lastChainProbeStatus = value; }
    /** Returns the prepared raw transaction hash used in settlement verification. */
    public String getPreparedRawTxHash() { return preparedRawTxHash; }
    /** Records the prepared raw transaction hash.
     * @param value transaction hash captured during preparation
     */
    public void setPreparedRawTxHash(String value) { this.preparedRawTxHash = value; }
    /** Returns whether confirmation and disappearance monitoring is enabled. */
    public boolean isConfirmationMonitoringActive() { return confirmationMonitoringActive; }
    /** Enables or disables background confirmation monitoring.
     * @param value {@code true} to continue monitoring
     */
    public void setConfirmationMonitoringActive(boolean value) { this.confirmationMonitoringActive = value; }
    /** Returns the time the transaction entered a conflicted state. */
    public LocalDateTime getConflictedAt() { return conflictedAt; }
    /** Records when the transaction became conflicted.
     * @param value conflict time in UTC, or {@code null} when not conflicted
     */
    public void setConflictedAt(LocalDateTime value) { this.conflictedAt = value; }
    /** Returns the replacement transaction ID, when this transaction was replaced. */
    public String getReplacementTxid() { return replacementTxid; }
    /** Records the replacement transaction ID.
     * @param value replacement transaction ID
     */
    public void setReplacementTxid(String value) { this.replacementTxid = value; }

    /** Returns the UTC creation timestamp populated before insert. */
    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    /** Returns the UTC timestamp of the most recent persisted update. */
    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }
}
