package com.kerosene.kfe.adapters.out.persistence.model.liquidity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * Persists the request and worker state for rebalancing an existing Lightning channel.
 *
 * <p>The record identifies the channel and optional peer, retains cost/gain estimates from the
 * decision, and tracks provider execution. Worker claims use a token and expiry lease; the JPA
 * version field protects concurrent state changes. Lifecycle timestamps are written in UTC, and
 * terminal transition helpers retain a bounded failure message when execution fails.</p>
 */
@Entity
@Table(name = "channel_rebalance_jobs", schema = "financial")
public class KfeChannelRebalanceJobEntity {

    /** Stable UUID assigned before insertion and used as this job's primary key. */
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id = UUID.randomUUID();

    /** Optional policy decision that caused this rebalance request. */
    @Column(name = "decision_id")
    private UUID decisionId;

    /** Required outpoint identifying the existing Lightning channel to rebalance. */
    @Column(name = "channel_point", nullable = false, length = 128)
    private String channelPoint;

    /** Optional peer public key used to route or constrain the rebalance. */
    @Column(name = "peer_pubkey", length = 128)
    private String peerPubkey;

    /** Estimated routing cost for the rebalance, expressed in satoshis. */
    @Column(name = "estimated_cost_sats", nullable = false)
    private long estimatedCostSats;

    /** Expected liquidity or routing benefit used to justify the rebalance, in satoshis. */
    @Column(name = "expected_gain_sats", nullable = false)
    private long expectedGainSats;

    /** Worker lifecycle state, initially pending and stored by enum name. */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private KfeChannelRebalanceJobStatus status = KfeChannelRebalanceJobStatus.PENDING;

    /** Provider-issued identifier used to reconcile the rebalance operation. */
    @Column(name = "provider_reference", length = 255)
    private String providerReference;

    /** Latest bounded failure detail, when the provider operation fails. */
    @Column(name = "last_error", length = 1000)
    private String lastError;

    /** UTC time when the job was inserted; remains fixed for stable job history. */
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /** UTC time of the latest persisted modification. */
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /** UTC time of a terminal success or failure; unset while execution is active. */
    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    /** Identity of the worker currently holding the execution lease, if claimed. */
    @Column(name = "claimed_by", length = 128)
    private String claimedBy;
    /** Opaque token identifying the current claim owner for safe lease recovery. */
    @Column(name = "claim_token")
    private UUID claimToken;
    /** UTC deadline after which another worker may recover an abandoned claim. */
    @Column(name = "lease_expires_at")
    private LocalDateTime leaseExpiresAt;
    /** Optimistic-lock version maintained by JPA to detect concurrent writes. */
    @Version
    @Column(name = "row_version", nullable = false)
    private long rowVersion;

    @Column(name = "execution_phase", nullable = false, length = 32)
    private String executionPhase = "READY";
    @Column(name = "fee_wallet_id")
    private UUID feeWalletId;
    @Column(name = "reserved_fee_sats", nullable = false)
    private long reservedFeeSats;
    @Column(name = "payment_hash", length = 64)
    private String paymentHash;

    @Column(name = "actual_fee_sats")
    private Long actualFeeSats;
    public Long getActualFeeSats() { return actualFeeSats; }
    public void setActualFeeSats(long value) { actualFeeSats = value; }

    public String getExecutionPhase() { return executionPhase; }
    public void setExecutionPhase(String phase) { executionPhase = phase; }
    public UUID getFeeWalletId() { return feeWalletId; }
    public void setFeeWalletId(UUID id) { feeWalletId = id; }
    public long getReservedFeeSats() { return reservedFeeSats; }
    public void setReservedFeeSats(long amount) { reservedFeeSats = amount; }
    public String getPaymentHash() { return paymentHash; }
    public void setPaymentHash(String hash) { paymentHash = hash; }

    /** Initializes creation and modification times from the same UTC instant on insertion. */
    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now(java.time.ZoneOffset.UTC);
        createdAt = now;
        updatedAt = now;
    }

    /** Refreshes the modification timestamp in UTC before updating the job. */
    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now(java.time.ZoneOffset.UTC);
    }

    /**
     * Records provider acceptance and moves the job into active execution.
     * @param providerReference provider operation identifier, or {@code null} if not yet issued
     */
    public void markInProgress(String providerReference) {
        status = KfeChannelRebalanceJobStatus.IN_PROGRESS;
        this.providerReference = providerReference;
    }

    /**
     * Marks the rebalance successful and captures provider reference and completion time.
     * @param providerReference provider operation identifier, or {@code null} if unavailable
     */
    public void markCompleted(String providerReference) {
        status = KfeChannelRebalanceJobStatus.COMPLETED;
        this.providerReference = providerReference;
        completedAt = LocalDateTime.now(java.time.ZoneOffset.UTC);
    }

    /**
     * Marks the rebalance failed and stores no more than the mapped 1000 characters of error text.
     * @param error failure detail, which may be {@code null}
     */
    public void markFailed(String error) {
        status = KfeChannelRebalanceJobStatus.FAILED;
        lastError = error != null && error.length() > 1000 ? error.substring(0, 1000) : error;
        completedAt = LocalDateTime.now(java.time.ZoneOffset.UTC);
    }

    /** @return stable UUID primary key for this rebalance job */
    public UUID getId() {
        return id;
    }

    /** @return decision identifier that triggered this job, if available */
    public UUID getDecisionId() {
        return decisionId;
    }

    /** @param decisionId optional triggering policy decision identifier */
    public void setDecisionId(UUID decisionId) {
        this.decisionId = decisionId;
    }

    /** @return outpoint of the existing channel being rebalanced */
    public String getChannelPoint() {
        return channelPoint;
    }

    /** @param channelPoint required channel outpoint to rebalance */
    public void setChannelPoint(String channelPoint) {
        this.channelPoint = channelPoint;
    }

    /** @return peer public key constraint, if one was recorded */
    public String getPeerPubkey() {
        return peerPubkey;
    }

    /** @param peerPubkey optional Lightning peer public key */
    public void setPeerPubkey(String peerPubkey) {
        this.peerPubkey = peerPubkey;
    }

    /** @return estimated rebalance cost in satoshis */
    public long getEstimatedCostSats() {
        return estimatedCostSats;
    }

    /** @param estimatedCostSats expected routing cost in satoshis */
    public void setEstimatedCostSats(long estimatedCostSats) {
        this.estimatedCostSats = estimatedCostSats;
    }

    /** @return expected liquidity or routing gain in satoshis */
    public long getExpectedGainSats() {
        return expectedGainSats;
    }

    /** @param expectedGainSats expected benefit in satoshis */
    public void setExpectedGainSats(long expectedGainSats) {
        this.expectedGainSats = expectedGainSats;
    }

    /** @return current persisted lifecycle state */
    public KfeChannelRebalanceJobStatus getStatus() {
        return status;
    }

    /** @param status lifecycle state to persist */
    public void setStatus(KfeChannelRebalanceJobStatus status) {
        this.status = status;
    }

    /** @return provider-issued reference, if execution has reached the provider */
    public String getProviderReference() {
        return providerReference;
    }

    /** @return immutable UTC insertion time */
    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    /** @return identity of the worker holding the job lease, if any */
    public String getClaimedBy() { return claimedBy; }
    /** @param claimedBy worker identity assigned when this job is claimed */
    public void setClaimedBy(String claimedBy) { this.claimedBy = claimedBy; }
    /** @return token identifying ownership of the current claim */
    public UUID getClaimToken() { return claimToken; }
    /** @param claimToken opaque token assigned to the active worker claim */
    public void setClaimToken(UUID claimToken) { this.claimToken = claimToken; }
    /** @return UTC expiry time for the current worker lease */
    public LocalDateTime getLeaseExpiresAt() { return leaseExpiresAt; }
    /** @param leaseExpiresAt UTC deadline after which an abandoned claim may be recovered */
    public void setLeaseExpiresAt(LocalDateTime leaseExpiresAt) { this.leaseExpiresAt = leaseExpiresAt; }
    /** @return current optimistic-lock version */
    public long getRowVersion() { return rowVersion; }
}
