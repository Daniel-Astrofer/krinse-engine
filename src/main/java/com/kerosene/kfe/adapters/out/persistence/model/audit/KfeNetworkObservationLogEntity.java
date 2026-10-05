package com.kerosene.kfe.adapters.out.persistence.model.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Append-only, privacy-safe record of external settlement observations for reconciliation and audit.
 * Indexes support transaction- and network-transaction-centric timelines; this row intentionally
 * stores a transaction reference and chain state without a wallet address, invoice, or user payload.
 */
@Entity
@Table(name = "kfe_network_observation_log", schema = "financial", indexes = {
        @Index(name = "idx_kfe_network_obs_tx", columnList = "transaction_id, observed_at"),
        @Index(name = "idx_kfe_network_obs_ref", columnList = "txid, observed_at")
})
public class KfeNetworkObservationLogEntity {

    /** Immutable database identifier generated locally when a new observation row is constructed. */
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id = UUID.randomUUID();

    /** KFE transaction whose external rail state was observed. */
    @Column(name = "transaction_id", nullable = false)
    private UUID transactionId;

    /** External chain transaction identifier associated with the observation. */
    @Column(name = "txid", nullable = false, length = 64)
    private String txid;

    /** Normalized external settlement state at observation time. */
    @Column(name = "state", nullable = false, length = 32)
    private String state;

    /** Chain confirmation count reported by the observing provider. */
    @Column(name = "confirmations", nullable = false)
    private int confirmations;

    /** Hash of the containing block when the provider has included the transaction. */
    @Column(name = "block_hash", length = 64)
    private String blockHash;

    /** Height of the containing block, null while the observation is unconfirmed or unavailable. */
    @Column(name = "block_height")
    private Integer blockHeight;

    /** Instant the observation was recorded; initialized to current time for new entity instances. */
    @Column(name = "observed_at", nullable = false)
    private Instant observedAt = Instant.now();

    /** @return generated identifier of this append-only observation row */
    public UUID getId() { return id; }
    /** @return KFE transaction associated with this external observation */
    public UUID getTransactionId() { return transactionId; }
    /** @param transactionId KFE transaction identifier associated with the observation */
    public void setTransactionId(UUID transactionId) { this.transactionId = transactionId; }
    /** @return external transaction ID observed from the provider */
    public String getTxid() { return txid; }
    /** @param txid external chain transaction identifier */
    public void setTxid(String txid) { this.txid = txid; }
    /** @return normalized provider state captured at observation time */
    public String getState() { return state; }
    /** @param state normalized external settlement state */
    public void setState(String state) { this.state = state; }
    /** @return confirmation count observed from the external chain */
    public int getConfirmations() { return confirmations; }
    /** @param confirmations chain confirmation count to persist */
    public void setConfirmations(int confirmations) { this.confirmations = confirmations; }
    /** @return containing block hash, or null when not yet known */
    public String getBlockHash() { return blockHash; }
    /** @param blockHash containing block hash when available */
    public void setBlockHash(String blockHash) { this.blockHash = blockHash; }
    /** @return containing block height, or null when not yet known */
    public Integer getBlockHeight() { return blockHeight; }
    /** @param blockHeight containing block height, or null while the transaction is unconfirmed */
    public void setBlockHeight(Integer blockHeight) { this.blockHeight = blockHeight; }
    /** @return instant this external state was observed */
    public Instant getObservedAt() { return observedAt; }
    /** @param observedAt observation instant to persist */
    public void setObservedAt(Instant observedAt) { this.observedAt = observedAt; }
}
