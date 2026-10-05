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
 * Persists a requested channel-capacity operation and its worker execution state.
 *
 * <p>The row carries the requested open/close intent, satoshi estimates, optional channel/provider
 * context, and a lease claim used by workers. Lifecycle timestamps use UTC and {@code rowVersion}
 * enables optimistic locking so concurrent workers cannot silently overwrite one another. The
 * transition helpers record terminal completion time and bound provider error text to the column.</p>
 */
@Entity
@Table(name = "channel_capacity_jobs", schema = "financial")
public class KfeChannelCapacityJobEntity {

    /** Stable UUID assigned before insertion and used as the job's primary key. */
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id = UUID.randomUUID();

    /** Optional capacity decision that caused this operation to be queued. */
    @Column(name = "decision_id")
    private UUID decisionId;

    /** Requested operation, persisted by enum name for schema readability. */
    @Enumerated(EnumType.STRING)
    @Column(name = "intent", nullable = false, length = 16)
    private KfeChannelCapacityIntent intent;

    /** Optional Lightning peer public key targeted by the capacity operation. */
    @Column(name = "peer_pubkey", length = 128)
    private String peerPubkey;

    /** Optional channel outpoint identifying an existing channel, especially for close requests. */
    @Column(name = "channel_point", length = 128)
    private String channelPoint;

    /** Requested local channel capacity in satoshis. */
    @Column(name = "local_amount_sats", nullable = false)
    private long localAmountSats;

    /** Estimated operation cost in satoshis captured when the request is created. */
    @Column(name = "estimated_cost_sats", nullable = false)
    private long estimatedCostSats;

    /** Expected liquidity or routing gain in satoshis used by the capacity decision. */
    @Column(name = "expected_gain_sats", nullable = false)
    private long expectedGainSats;

    /** Optional explanation of the signal that triggered this capacity job. */
    @Column(name = "trigger_reason", length = 255)
    private String triggerReason;

    /** Current worker lifecycle state, initially pending and stored by enum name. */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private KfeChannelCapacityJobStatus status = KfeChannelCapacityJobStatus.PENDING;

    /** Identifier returned by the external provider after it accepts the operation. */
    @Column(name = "provider_reference", length = 255)
    private String providerReference;

    /** Most recent bounded failure detail, when execution fails. */
    @Column(name = "last_error", length = 1000)
    private String lastError;

    /** UTC creation time assigned on first persistence and then kept fixed. */
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /** UTC time of the latest persisted job change. */
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /** UTC terminal transition time, left unset while work remains pending or active. */
    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    /** Worker identity currently holding the job lease, if claimed. */
    @Column(name = "claimed_by", length = 128)
    private String claimedBy;
    /** Opaque ownership token used to distinguish the active lease claimant. */
    @Column(name = "claim_token")
    private UUID claimToken;
    /** UTC deadline after which an uncompleted worker claim may be recovered. */
    @Column(name = "lease_expires_at")
    private LocalDateTime leaseExpiresAt;
    /** Optimistic-lock version incremented by persistence when another writer changes the row. */
    @Version
    @Column(name = "row_version", nullable = false)
    private long rowVersion;

    /** Initializes creation and modification timestamps from the same UTC instant on insertion. */
    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now(java.time.ZoneOffset.UTC);
        createdAt = now;
        updatedAt = now;
    }

    /** Refreshes the modification timestamp in UTC before each persisted update. */
    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now(java.time.ZoneOffset.UTC);
    }

    /**
     * Records that a provider accepted the job and moved execution into progress.
     *
     * @param providerReference provider-side operation reference, which may be {@code null} when
     *                          the provider has not issued one yet
     */
    public void markInProgress(String providerReference) {
        status = KfeChannelCapacityJobStatus.IN_PROGRESS;
        this.providerReference = providerReference;
    }

    /**
     * Marks the operation complete and records its provider reference and UTC completion time.
     *
     * @param providerReference provider-side operation reference, or {@code null} if unavailable
     */
    public void markCompleted(String providerReference) {
        status = KfeChannelCapacityJobStatus.COMPLETED;
        this.providerReference = providerReference;
        completedAt = LocalDateTime.now(java.time.ZoneOffset.UTC);
    }

    /**
     * Marks the operation failed, truncates error detail to the database limit, and timestamps it.
     *
     * @param error failure detail, optionally {@code null}; values longer than 1000 characters are
     *              truncated before persistence
     */
    public void markFailed(String error) {
        status = KfeChannelCapacityJobStatus.FAILED;
        lastError = error != null && error.length() > 1000 ? error.substring(0, 1000) : error;
        completedAt = LocalDateTime.now(java.time.ZoneOffset.UTC);
    }

    /** @return stable UUID primary key for this job */
    public UUID getId() {
        return id;
    }

    /** @return capacity decision identifier that triggered the job, if present */
    public UUID getDecisionId() {
        return decisionId;
    }

    /** @param decisionId optional identifier of the capacity decision associated with this job */
    public void setDecisionId(UUID decisionId) {
        this.decisionId = decisionId;
    }

    /** @return requested channel operation */
    public KfeChannelCapacityIntent getIntent() {
        return intent;
    }

    /** @param intent non-null open or close intent to execute */
    public void setIntent(KfeChannelCapacityIntent intent) {
        this.intent = intent;
    }

    /** @return targeted Lightning peer public key, if supplied */
    public String getPeerPubkey() {
        return peerPubkey;
    }

    /** @param peerPubkey optional Lightning peer public key */
    public void setPeerPubkey(String peerPubkey) {
        this.peerPubkey = peerPubkey;
    }

    /** @return identified channel outpoint, if the operation targets an existing channel */
    public String getChannelPoint() {
        return channelPoint;
    }

    /** @param channelPoint optional channel outpoint for the requested operation */
    public void setChannelPoint(String channelPoint) {
        this.channelPoint = channelPoint;
    }

    /** @return requested local capacity in satoshis */
    public long getLocalAmountSats() {
        return localAmountSats;
    }

    /** @param localAmountSats requested local capacity in satoshis */
    public void setLocalAmountSats(long localAmountSats) {
        this.localAmountSats = localAmountSats;
    }

    /** @return captured estimated cost in satoshis */
    public long getEstimatedCostSats() {
        return estimatedCostSats;
    }

    /** @param estimatedCostSats estimated operation cost in satoshis */
    public void setEstimatedCostSats(long estimatedCostSats) {
        this.estimatedCostSats = estimatedCostSats;
    }

    /** @return expected gain in satoshis used when evaluating the operation */
    public long getExpectedGainSats() {
        return expectedGainSats;
    }

    /** @param expectedGainSats expected liquidity or routing gain in satoshis */
    public void setExpectedGainSats(long expectedGainSats) {
        this.expectedGainSats = expectedGainSats;
    }

    /** @return reason or signal that caused the capacity request, if recorded */
    public String getTriggerReason() {
        return triggerReason;
    }

    /** @param triggerReason optional reason, limited to the mapped 255-character column */
    public void setTriggerReason(String triggerReason) {
        this.triggerReason = triggerReason;
    }

    /** @return current job lifecycle state */
    public KfeChannelCapacityJobStatus getStatus() {
        return status;
    }

    /** @param status lifecycle state to persist */
    public void setStatus(KfeChannelCapacityJobStatus status) {
        this.status = status;
    }

    /** @return provider-issued operation reference, if execution reached the provider */
    public String getProviderReference() {
        return providerReference;
    }

    /** @return latest bounded failure detail, or {@code null} when no failure is recorded */
    public String getLastError() {
        return lastError;
    }

    /** @return immutable UTC time when the job was created */
    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    /** @return identity of the worker holding the lease, if currently claimed */
    public String getClaimedBy() { return claimedBy; }
    /** @param claimedBy worker identity that claimed this job */
    public void setClaimedBy(String claimedBy) { this.claimedBy = claimedBy; }
    /** @return opaque token proving ownership of the current worker claim */
    public UUID getClaimToken() { return claimToken; }
    /** @param claimToken ownership token assigned to the current claim */
    public void setClaimToken(UUID claimToken) { this.claimToken = claimToken; }
    /** @return UTC time when the current worker claim expires */
    public LocalDateTime getLeaseExpiresAt() { return leaseExpiresAt; }
    /** @param leaseExpiresAt UTC expiry instant for the worker claim */
    public void setLeaseExpiresAt(LocalDateTime leaseExpiresAt) { this.leaseExpiresAt = leaseExpiresAt; }
    /** @return optimistic-lock version maintained by JPA */
    public long getRowVersion() { return rowVersion; }
}
