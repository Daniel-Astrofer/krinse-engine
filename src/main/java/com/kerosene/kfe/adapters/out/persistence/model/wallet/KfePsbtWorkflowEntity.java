package com.kerosene.kfe.adapters.out.persistence.model.wallet;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * Persists the lifecycle of a PSBT-based wallet transaction from preparation to broadcast.
 *
 * <p>The workflow binds a PSBT and its digest to a user, wallet, amount, fee, and destination. A
 * signed PSBT and its digest record signer output; finalized raw transaction data and its digest
 * support settlement verification; broadcast identifiers and timestamps record network dispatch.
 * The stored inputs snapshot and failure message aid review and recovery. Timestamps are maintained
 * in UTC by lifecycle callbacks.</p>
 */
@Entity
@Table(name = "kfe_psbt_workflows", schema = "financial", indexes = {
        @Index(name = "idx_psbt_workflows_wallet_created", columnList = "wallet_id, created_at"),
        @Index(name = "idx_psbt_workflows_status", columnList = "status")
})
public class KfePsbtWorkflowEntity {

    /** Stable UUID assigned before insert and used as the workflow primary key. */
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id = UUID.randomUUID();

    /** User who initiated and owns this signing workflow. */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** Wallet whose funds and signing policy apply to this workflow. */
    @Column(name = "wallet_id", nullable = false)
    private UUID walletId;

    /** Current signing/broadcast lifecycle state, persisted as its enum name. */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private KfePsbtWorkflowStatus status = KfePsbtWorkflowStatus.CREATED;

    /** Required unsigned or partially signed PSBT that defines the proposed spend. */
    @Column(name = "psbt", nullable = false, columnDefinition = "TEXT")
    private String psbt;

    /** PSBT returned by the signing step, when signing has completed. */
    @Column(name = "signed_psbt", columnDefinition = "TEXT")
    private String signedPsbt;

    /** Finalized raw transaction bytes encoded as hex, when finalization succeeds. */
    @Column(name = "raw_tx_hex", columnDefinition = "TEXT")
    private String rawTxHex;

    /** Required digest of the original PSBT, used to bind later steps to the reviewed proposal. */
    @Column(name = "psbt_hash", nullable = false, length = 64)
    private String psbtHash;

    /** Digest of the signed PSBT for integrity comparison after signing. */
    @Column(name = "signed_psbt_hash", length = 64)
    private String signedPsbtHash;

    /** Digest of the finalized raw transaction used during settlement checks. */
    @Column(name = "raw_tx_hash", length = 64)
    private String rawTxHash;

    /** Network transaction identifier returned by broadcast, when known. */
    @Column(name = "broadcast_txid", length = 128)
    private String broadcastTxid;

    /** Amount being sent to the destination, in satoshis. */
    @Column(name = "amount_sats", nullable = false)
    private long amountSats;

    /** Fee allocated to this transaction, in satoshis. */
    @Column(name = "fee_sats", nullable = false)
    private long feeSats;

    /** Required Bitcoin destination represented by the PSBT output. */
    @Column(name = "destination_address", nullable = false, length = 128)
    private String destinationAddress;

    /** Optional JSON snapshot describing inputs selected for the proposed transaction. */
    @Column(name = "inputs_json", columnDefinition = "TEXT")
    private String inputsJson;

    /** Bounded explanation captured when a workflow step fails. */
    @Column(name = "failure_message", length = 255)
    private String failureMessage;

    /** Immutable UTC creation time of the signing workflow. */
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /** UTC time of the most recent lifecycle update. */
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /** UTC time when signed PSBT output was recorded. */
    @Column(name = "signed_at")
    private LocalDateTime signedAt;

    /** UTC time when the finalized transaction was submitted to the network. */
    @Column(name = "broadcast_at")
    private LocalDateTime broadcastAt;

    /** Sets creation and modification timestamps from the same UTC instant before insertion. */
    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now(java.time.ZoneOffset.UTC);
        createdAt = now;
        updatedAt = now;
    }

    /** Refreshes the modification timestamp in UTC before each workflow update. */
    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now(java.time.ZoneOffset.UTC);
    }

    /** @return stable workflow UUID */
    public UUID getId() {
        return id;
    }

    /** @return initiating user identifier */
    public Long getUserId() {
        return userId;
    }

    /** @param userId user who owns the workflow */
    public void setUserId(Long userId) {
        this.userId = userId;
    }

    /** @return wallet used for signing and funding */
    public UUID getWalletId() {
        return walletId;
    }

    /** @param walletId wallet whose signing policy applies */
    public void setWalletId(UUID walletId) {
        this.walletId = walletId;
    }

    /** @return current workflow lifecycle state */
    public KfePsbtWorkflowStatus getStatus() {
        return status;
    }

    /** @param status workflow state reached by the current operation */
    public void setStatus(KfePsbtWorkflowStatus status) {
        this.status = status;
    }

    /** @return original PSBT proposal retained for review and signing */
    public String getPsbt() {
        return psbt;
    }

    /** @param psbt unsigned or partially signed PSBT proposal */
    public void setPsbt(String psbt) {
        this.psbt = psbt;
    }

    /** @return signer-produced PSBT, if the signing phase has completed */
    public String getSignedPsbt() {
        return signedPsbt;
    }

    /** @param signedPsbt signer-produced PSBT to retain */
    public void setSignedPsbt(String signedPsbt) {
        this.signedPsbt = signedPsbt;
    }

    /** @return finalized transaction encoded as hexadecimal, if available */
    public String getRawTxHex() {
        return rawTxHex;
    }

    /** @param rawTxHex finalized raw transaction bytes encoded as hexadecimal */
    public void setRawTxHex(String rawTxHex) {
        this.rawTxHex = rawTxHex;
    }

    /** @return digest binding this workflow to its original PSBT */
    public String getPsbtHash() {
        return psbtHash;
    }

    /** @param psbtHash digest of the original PSBT proposal */
    public void setPsbtHash(String psbtHash) {
        this.psbtHash = psbtHash;
    }

    /** @return integrity digest of the signer-produced PSBT, if calculated */
    public String getSignedPsbtHash() {
        return signedPsbtHash;
    }

    /** @param signedPsbtHash digest calculated for the signed PSBT */
    public void setSignedPsbtHash(String signedPsbtHash) {
        this.signedPsbtHash = signedPsbtHash;
    }

    /** @return digest of the finalized raw transaction, if calculated */
    public String getRawTxHash() {
        return rawTxHash;
    }

    /** @param rawTxHash digest calculated for the finalized transaction */
    public void setRawTxHash(String rawTxHash) {
        this.rawTxHash = rawTxHash;
    }

    /** @return transaction identifier acknowledged by the network after broadcast */
    public String getBroadcastTxid() {
        return broadcastTxid;
    }

    /** @param broadcastTxid network transaction identifier returned by broadcast */
    public void setBroadcastTxid(String broadcastTxid) {
        this.broadcastTxid = broadcastTxid;
    }

    /** @return destination amount in satoshis */
    public long getAmountSats() {
        return amountSats;
    }

    /** @param amountSats amount represented by the destination output, in satoshis */
    public void setAmountSats(long amountSats) {
        this.amountSats = amountSats;
    }

    /** @return transaction fee in satoshis */
    public long getFeeSats() {
        return feeSats;
    }

    /** @param feeSats fee allocated to this transaction, in satoshis */
    public void setFeeSats(long feeSats) {
        this.feeSats = feeSats;
    }

    /** @return Bitcoin address represented by the destination output */
    public String getDestinationAddress() {
        return destinationAddress;
    }

    /** @param destinationAddress required Bitcoin destination address */
    public void setDestinationAddress(String destinationAddress) {
        this.destinationAddress = destinationAddress;
    }

    /** @return selected input metadata as JSON, if recorded */
    public String getInputsJson() {
        return inputsJson;
    }

    /** @param inputsJson optional JSON snapshot describing selected funding inputs */
    public void setInputsJson(String inputsJson) {
        this.inputsJson = inputsJson;
    }

    /** @return recorded workflow failure detail, or {@code null} when no failure occurred */
    public String getFailureMessage() {
        return failureMessage;
    }

    /** @param failureMessage bounded failure explanation for the workflow */
    public void setFailureMessage(String failureMessage) {
        this.failureMessage = failureMessage;
    }

    /** @return immutable UTC workflow creation time */
    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    /** @return UTC time of the latest workflow update */
    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    /** @return signing completion time, or {@code null} before signing */
    public LocalDateTime getSignedAt() {
        return signedAt;
    }

    /** @param signedAt UTC time when the signed PSBT was produced */
    public void setSignedAt(LocalDateTime signedAt) {
        this.signedAt = signedAt;
    }

    /** @return broadcast submission time, or {@code null} before network dispatch */
    public LocalDateTime getBroadcastAt() {
        return broadcastAt;
    }

    /** @param broadcastAt UTC time when the transaction was submitted to the network */
    public void setBroadcastAt(LocalDateTime broadcastAt) {
        this.broadcastAt = broadcastAt;
    }
}
