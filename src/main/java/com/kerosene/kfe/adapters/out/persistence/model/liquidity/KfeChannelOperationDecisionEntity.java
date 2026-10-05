package com.kerosene.kfe.adapters.out.persistence.model.liquidity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * Stores the result and execution context of a channel open or close policy decision.
 *
 * <p>The decision snapshot includes the evaluated flags, rationale, idempotency key, and provider
 * references needed to safely continue an operation across retries. Mesh intent and funding fields
 * track the staged mesh-to-LND open flow; they are optional for decisions that do not use that
 * integration. A passed decision records policy approval, while {@code executed} records whether
 * the approved action was actually carried out.</p>
 */
@Entity
@Table(name = "channel_operation_decisions", schema = "financial")
public class KfeChannelOperationDecisionEntity {

    /** Stable UUID allocated when the decision record is created. */
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id = UUID.randomUUID();

    /** Channel operation whose policy evaluation produced this decision. */
    @Enumerated(EnumType.STRING)
    @Column(name = "operation", nullable = false, length = 32)
    private KfeChannelOperationType operation;

    /** Whether all required policy checks allowed the requested operation. */
    @Column(name = "passed", nullable = false)
    private boolean passed;

    /** Optional Lightning peer targeted by the decision. */
    @Column(name = "peer_pubkey", length = 128)
    private String peerPubkey;

    /** Optional existing channel outpoint, typically needed for close decisions. */
    @Column(name = "channel_point", length = 128)
    private String channelPoint;

    /** Optional channel amount evaluated by the policy, expressed in satoshis. */
    @Column(name = "amount_sats")
    private Long amountSats;

    /** Required serialized snapshot of policy flags and their evaluation results. */
    @Column(name = "flags_json", nullable = false, columnDefinition = "TEXT")
    private String flagsJson;

    /** Human-readable explanation of the approval or rejection outcome. */
    @Column(name = "decision_reason", length = 255)
    private String decisionReason;

    /** Whether the approved operation has been dispatched or completed by its executor. */
    @Column(name = "executed", nullable = false)
    private boolean executed;

    /** Optional external provider reference used to reconcile execution. */
    @Column(name = "provider_reference", length = 255)
    private String providerReference;

    /** Stable command key used to make repeated lifecycle commands idempotent. */
    @Column(name = "idempotency_key", nullable = false, unique = true, length = 128)
    private String idempotencyKey;

    /** Stable mesh Intent id ({@code channels-inject-open-<decisionId>}). */
    @Column(name = "mesh_intent_id", length = 160)
    private String meshIntentId;

    /**
     * Inject phase: {@code RESERVED}, {@code FUNDED}, {@code OPENED_COMMIT_PENDING},
     * {@code COMMITTED}, {@code RELEASED}.
     */
    @Column(name = "mesh_inject_phase", length = 40)
    private String meshInjectPhase;

    /** LND wallet address bound to this CHANNELS withdraw / open. */
    @Column(name = "lnd_funding_address", length = 128)
    private String lndFundingAddress;

    /** Optional on-chain fund txid when mesh→LND PSBT lands; null for bind-only slice. */
    @Column(name = "mesh_fund_txid", length = 128)
    private String meshFundTxid;

    /** UTC insertion time of this policy decision snapshot. */
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /** Assigns a UTC creation timestamp immediately before the decision is inserted. */
    @PrePersist
    void onCreate() {
        createdAt = LocalDateTime.now(java.time.ZoneOffset.UTC);
    }

    /** @return stable UUID of this decision record */
    public UUID getId() {
        return id;
    }

    /** @return channel operation evaluated by policy */
    public KfeChannelOperationType getOperation() {
        return operation;
    }

    /** @param operation operation whose eligibility is being recorded */
    public void setOperation(KfeChannelOperationType operation) {
        this.operation = operation;
    }

    /** @return {@code true} when policy checks approved the operation */
    public boolean isPassed() {
        return passed;
    }

    /** @param passed whether policy approved the operation */
    public void setPassed(boolean passed) {
        this.passed = passed;
    }

    /** @return targeted peer public key, if applicable */
    public String getPeerPubkey() {
        return peerPubkey;
    }

    /** @param peerPubkey optional Lightning peer public key */
    public void setPeerPubkey(String peerPubkey) {
        this.peerPubkey = peerPubkey;
    }

    /** @return existing channel outpoint, if the operation identifies one */
    public String getChannelPoint() {
        return channelPoint;
    }

    /** @param channelPoint optional outpoint of the channel under evaluation */
    public void setChannelPoint(String channelPoint) {
        this.channelPoint = channelPoint;
    }

    /** @return amount evaluated by policy in satoshis, or {@code null} when unspecified */
    public Long getAmountSats() {
        return amountSats;
    }

    /** @param amountSats optional channel amount in satoshis */
    public void setAmountSats(Long amountSats) {
        this.amountSats = amountSats;
    }

    /** @return serialized policy flag snapshot */
    public String getFlagsJson() {
        return flagsJson;
    }

    /** @param flagsJson required JSON representation of evaluated policy flags */
    public void setFlagsJson(String flagsJson) {
        this.flagsJson = flagsJson;
    }

    /** @return explanation of why policy approved or rejected the operation */
    public String getDecisionReason() {
        return decisionReason;
    }

    /** @param decisionReason optional human-readable decision rationale */
    public void setDecisionReason(String decisionReason) {
        this.decisionReason = decisionReason;
    }

    /** @return whether the approved operation has been executed */
    public boolean isExecuted() {
        return executed;
    }

    /** @param executed whether execution has been dispatched or completed */
    public void setExecuted(boolean executed) {
        this.executed = executed;
    }

    /** @return external provider execution reference, if available */
    public String getProviderReference() {
        return providerReference;
    }

    /** @param providerReference external reference used to reconcile provider execution */
    public void setProviderReference(String providerReference) {
        this.providerReference = providerReference;
    }

    /** @return unique stable command key used to prevent duplicate lifecycle actions */
    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    /** @param idempotencyKey required stable key reused by retries of the same command */
    public void setIdempotencyKey(String idempotencyKey) {
        this.idempotencyKey = idempotencyKey;
    }

    /** @return mesh intent identifier for the staged channel-funding flow, if used */
    public String getMeshIntentId() {
        return meshIntentId;
    }

    /** @param meshIntentId stable mesh intent identifier associated with this decision */
    public void setMeshIntentId(String meshIntentId) {
        this.meshIntentId = meshIntentId;
    }

    /** @return latest recorded mesh injection phase, if the flow uses mesh funding */
    public String getMeshInjectPhase() {
        return meshInjectPhase;
    }

    /** @param meshInjectPhase latest staged funding phase used for recovery and reconciliation */
    public void setMeshInjectPhase(String meshInjectPhase) {
        this.meshInjectPhase = meshInjectPhase;
    }

    /** @return LND address bound to the mesh withdrawal used to fund the channel */
    public String getLndFundingAddress() {
        return lndFundingAddress;
    }

    /** @param lndFundingAddress funding address bound to the staged channel-open flow */
    public void setLndFundingAddress(String lndFundingAddress) {
        this.lndFundingAddress = lndFundingAddress;
    }

    /** @return on-chain funding transaction identifier, when mesh funding produced one */
    public String getMeshFundTxid() {
        return meshFundTxid;
    }

    /** @param meshFundTxid on-chain transaction identifier for mesh-to-LND channel funding */
    public void setMeshFundTxid(String meshFundTxid) {
        this.meshFundTxid = meshFundTxid;
    }

    /** @return UTC insertion time for this decision */
    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
