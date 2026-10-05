package com.kerosene.kfe.adapters.out.persistence.model.paymentexecution;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * Persists payment execution commands until a worker dispatches and reconciles them.
 *
 * <p>The outbox binds one operation to a financial transaction, protects payload integrity with a
 * digest, and schedules retries after unsuccessful attempts. Claims use owner, token, and lease
 * expiry fields so stale workers can be fenced; encrypted prepared payload fields allow sensitive
 * provider material to be retained separately from the original command. UTC lifecycle timestamps
 * and optimistic row versioning support recovery and concurrent processing.</p>
 */
@Entity
@Table(name = "financial_execution_outbox", schema = "financial", indexes = {
        @Index(name = "idx_financial_execution_outbox_status", columnList = "status, next_attempt_at")
})
public class KfeExecutionOutboxEntity {

    /** Stable UUID assigned before insert and used as the outbox row's primary key. */
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id = UUID.randomUUID();

    /** Financial transaction for which this execution operation was created. */
    @Column(name = "transaction_id", nullable = false)
    private UUID transactionId;

    /** Required provider operation name or command category to be executed. */
    @Column(name = "operation", nullable = false, length = 64)
    private String operation;

    /** Worker lifecycle state, initially pending and updated as attempts progress. */
    @Column(name = "status", nullable = false, length = 32)
    private String status = "PENDING";

    /** Original serialized execution request, when the operation needs a JSON body. */
    @Column(name = "payload_json", columnDefinition = "TEXT")
    private String payloadJson;

    /** Required digest of the original request payload for integrity verification. */
    @Column(name = "payload_hash", nullable = false, length = 64)
    private String payloadHash;

    /** Provider-issued request reference used to reconcile an accepted operation. */
    @Column(name = "provider_reference", length = 255)
    private String providerReference;

    /** Number of worker dispatch attempts recorded for this command. */
    @Column(name = "attempts", nullable = false)
    private int attempts;

    /** UTC retry eligibility time after a transient failure or deferred result. */
    @Column(name = "next_attempt_at")
    private LocalDateTime nextAttemptAt;

    /** Identity of the worker currently processing this command, if leased. */
    @Column(name = "claimed_by", length = 128)
    private String claimedBy;

    /** UTC instant at which the current worker claim was acquired. */
    @Column(name = "claimed_at")
    private LocalDateTime claimedAt;

    /** Opaque current claim token that prevents stale workers from writing a result. */
    @Column(name = "claim_token")
    private UUID claimToken;

    /** UTC claim deadline after which an abandoned command can be reclaimed. */
    @Column(name = "lease_expires_at")
    private LocalDateTime leaseExpiresAt;

    /** Encrypted provider-ready request material stored separately from the original payload. */
    @Column(name = "prepared_payload_ciphertext", columnDefinition = "TEXT")
    private String preparedPayloadCiphertext;

    /** Digest of the encrypted prepared payload for integrity checks before dispatch. */
    @Column(name = "prepared_payload_hash", length = 64)
    private String preparedPayloadHash;

    /** Internal or provider execution identifier returned after dispatch. */
    @Column(name = "execution_reference", length = 255)
    private String executionReference;

    /** UTC instant when the command was last dispatched to the execution adapter. */
    @Column(name = "dispatched_at")
    private LocalDateTime dispatchedAt;

    /** Latest execution or provider failure detail retained for retry and diagnosis. */
    @Column(name = "last_error", columnDefinition = "TEXT")
    private String lastError;

    /** Immutable UTC creation time of the execution command. */
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /** UTC time of the latest persisted update to this command. */
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /** Optimistic-lock version for concurrent worker claim and result writes. */
    @Version
    @Column(name = "row_version", nullable = false)
    private long rowVersion;

    /** Initializes creation and modification timestamps from the same UTC instant on insert. */
    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now(java.time.ZoneOffset.UTC);
        createdAt = now;
        updatedAt = now;
    }

    /** Refreshes the modification timestamp in UTC before a worker state update. */
    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now(java.time.ZoneOffset.UTC);
    }

    /** @return stable UUID primary key for this execution command */
    public UUID getId() {
        return id;
    }

    /** @return financial transaction this operation belongs to */
    public UUID getTransactionId() {
        return transactionId;
    }

    /** @param transactionId transaction identifier that owns the execution request */
    public void setTransactionId(UUID transactionId) {
        this.transactionId = transactionId;
    }

    /** @return operation name to dispatch to the payment execution adapter */
    public String getOperation() {
        return operation;
    }

    /** @param operation required operation category to execute */
    public void setOperation(String operation) {
        this.operation = operation;
    }

    /** @return current processing state string */
    public String getStatus() {
        return status;
    }

    /** @param status state selected by the execution worker */
    public void setStatus(String status) {
        this.status = status;
    }

    /** @return original serialized execution request, if present */
    public String getPayloadJson() {
        return payloadJson;
    }

    /** @param payloadJson original request body to persist */
    public void setPayloadJson(String payloadJson) {
        this.payloadJson = payloadJson;
    }

    /** @return digest used to verify the original execution request */
    public String getPayloadHash() {
        return payloadHash;
    }

    /** @param payloadHash required digest of the request payload */
    public void setPayloadHash(String payloadHash) {
        this.payloadHash = payloadHash;
    }

    /** @return provider-issued reference for the operation, if available */
    public String getProviderReference() {
        return providerReference;
    }

    /** @param providerReference external provider reference used during reconciliation */
    public void setProviderReference(String providerReference) {
        this.providerReference = providerReference;
    }

    /** @return number of dispatch attempts already made */
    public int getAttempts() {
        return attempts;
    }

    /** @param attempts cumulative execution attempt count */
    public void setAttempts(int attempts) {
        this.attempts = attempts;
    }

    /** @return retry eligibility time, if a later attempt is scheduled */
    public LocalDateTime getNextAttemptAt() {
        return nextAttemptAt;
    }

    /** @param nextAttemptAt UTC time when the worker may attempt dispatch again */
    public void setNextAttemptAt(LocalDateTime nextAttemptAt) {
        this.nextAttemptAt = nextAttemptAt;
    }

    /** @return worker identity holding the active claim, if any */
    public String getClaimedBy() {
        return claimedBy;
    }

    /** @param claimedBy identity of the worker acquiring the command lease */
    public void setClaimedBy(String claimedBy) {
        this.claimedBy = claimedBy;
    }

    /** @return time at which the current claim was acquired */
    public LocalDateTime getClaimedAt() {
        return claimedAt;
    }

    /** @param claimedAt UTC claim acquisition time */
    public void setClaimedAt(LocalDateTime claimedAt) {
        this.claimedAt = claimedAt;
    }

    /** @return token identifying the current claim owner */
    public UUID getClaimToken() {
        return claimToken;
    }

    /** @param claimToken opaque token assigned to the current worker claim */
    public void setClaimToken(UUID claimToken) {
        this.claimToken = claimToken;
    }

    /** @return claim expiry time after which recovery may reassign the command */
    public LocalDateTime getLeaseExpiresAt() {
        return leaseExpiresAt;
    }

    /** @param leaseExpiresAt UTC expiry time for the active claim */
    public void setLeaseExpiresAt(LocalDateTime leaseExpiresAt) {
        this.leaseExpiresAt = leaseExpiresAt;
    }

    /** @return encrypted prepared request body, if preparation has occurred */
    public String getPreparedPayloadCiphertext() {
        return preparedPayloadCiphertext;
    }

    /** @param preparedPayloadCiphertext encrypted provider-ready payload to retain */
    public void setPreparedPayloadCiphertext(String preparedPayloadCiphertext) {
        this.preparedPayloadCiphertext = preparedPayloadCiphertext;
    }

    /** @return digest of the encrypted prepared payload */
    public String getPreparedPayloadHash() {
        return preparedPayloadHash;
    }

    /** @param preparedPayloadHash digest used to verify prepared encrypted material */
    public void setPreparedPayloadHash(String preparedPayloadHash) {
        this.preparedPayloadHash = preparedPayloadHash;
    }

    /** @return execution identifier assigned during or after dispatch */
    public String getExecutionReference() {
        return executionReference;
    }

    /** @param executionReference identifier returned by the execution adapter */
    public void setExecutionReference(String executionReference) {
        this.executionReference = executionReference;
    }

    /** @return most recent dispatch instant, if the worker sent the command */
    public LocalDateTime getDispatchedAt() {
        return dispatchedAt;
    }

    /** @param dispatchedAt UTC instant when the command was sent to the adapter */
    public void setDispatchedAt(LocalDateTime dispatchedAt) {
        this.dispatchedAt = dispatchedAt;
    }

    /** @return latest error detail, or {@code null} when no failure was recorded */
    public String getLastError() {
        return lastError;
    }

    /** @param lastError latest provider or dispatch failure detail */
    public void setLastError(String lastError) {
        this.lastError = lastError;
    }

    /** @return immutable UTC creation time */
    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    /** @return UTC time of the latest persisted command update */
    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    /** @return optimistic-lock version maintained by JPA */
    public long getRowVersion() {
        return rowVersion;
    }
}
