package com.kerosene.kfe.adapters.out.persistence.model.wallet;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * Persists a wallet-controlled or monitored receiving/change address and its lifecycle metadata.
 *
 * <p>The role identifies how wallet logic uses the address, while status controls whether it may
 * be issued or should only be retained for history/monitoring. Derivation path and index connect
 * HD-generated addresses to their cursor; provider and observation fields retain external lookup
 * context. Retirement is timestamped in UTC and preserves the address row rather than deleting it.</p>
 */
@Entity
@Table(name = "wallet_addresses", schema = "financial", indexes = {
        @Index(name = "idx_wallet_addresses_wallet_status", columnList = "wallet_id, status, created_at")
})
public class KfeWalletAddressEntity {

    /** Stable UUID assigned before insertion and used as this address record's primary key. */
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id = UUID.randomUUID();

    /** Wallet that owns the address and determines its derivation and access policy. */
    @Column(name = "wallet_id", nullable = false)
    private UUID walletId;

    /** Required on-chain or monitored address string stored for lookup and presentation. */
    @Column(name = "address", nullable = false, length = 128)
    private String address;

    /** Address purpose, such as receiving funds, returning change, or monitoring only. */
    @Enumerated(EnumType.STRING)
    @Column(name = "address_role", nullable = false, length = 32)
    private KfeWalletAddressRole addressRole = KfeWalletAddressRole.RECEIVE;

    /** Availability and observation lifecycle state, initially active. */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private KfeWalletAddressStatus status = KfeWalletAddressStatus.ACTIVE;

    /** HD derivation path that produced this address, if it is locally derivable. */
    @Column(name = "derivation_path", length = 160)
    private String derivationPath;

    /** Child index used for this address within its derivation branch. */
    @Column(name = "derivation_index")
    private Integer derivationIndex;

    /** Optional identifier supplied by an address or custody provider. */
    @Column(name = "provider_reference", length = 255)
    private String providerReference;

    /** First known transaction identifier observed paying to or spending from this address. */
    @Column(name = "first_seen_txid", length = 128)
    private String firstSeenTxid;

    /** UTC time of the most recent provider or chain observation for this address. */
    @Column(name = "last_seen_at")
    private LocalDateTime lastSeenAt;

    /** UTC time when this address record was first inserted. */
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /** UTC time when the address was retired from new issuance, if it has been retired. */
    @Column(name = "retired_at")
    private LocalDateTime retiredAt;

    /** Captures the UTC creation instant immediately before insertion. */
    @PrePersist
    void onCreate() {
        createdAt = LocalDateTime.now(java.time.ZoneOffset.UTC);
    }

    /** Marks the address retired and records the UTC time while retaining its historical row. */
    public void retire() {
        status = KfeWalletAddressStatus.RETIRED;
        retiredAt = LocalDateTime.now(java.time.ZoneOffset.UTC);
    }

    /** @return stable UUID primary key of the wallet address record */
    public UUID getId() {
        return id;
    }

    /** @return owner wallet identifier */
    public UUID getWalletId() {
        return walletId;
    }

    /** @param walletId wallet that owns this address */
    public void setWalletId(UUID walletId) {
        this.walletId = walletId;
    }

    /** @return stored address string */
    public String getAddress() {
        return address;
    }

    /** @param address required receiving, change, or monitored address string */
    public void setAddress(String address) {
        this.address = address;
    }

    /** @return purpose assigned to the address */
    public KfeWalletAddressRole getAddressRole() {
        return addressRole;
    }

    /** @param addressRole address purpose used by wallet behavior */
    public void setAddressRole(KfeWalletAddressRole addressRole) {
        this.addressRole = addressRole;
    }

    /** @return current issuance, observation, or restriction state */
    public KfeWalletAddressStatus getStatus() {
        return status;
    }

    /** @param status lifecycle state to persist for this address */
    public void setStatus(KfeWalletAddressStatus status) {
        this.status = status;
    }

    /** @return HD derivation path, or {@code null} for externally supplied addresses */
    public String getDerivationPath() {
        return derivationPath;
    }

    /** @param derivationPath HD path that produced the address, when applicable */
    public void setDerivationPath(String derivationPath) {
        this.derivationPath = derivationPath;
    }

    /** @return child derivation index, or {@code null} when not locally derived */
    public Integer getDerivationIndex() {
        return derivationIndex;
    }

    /** @param derivationIndex child index associated with the address derivation path */
    public void setDerivationIndex(Integer derivationIndex) {
        this.derivationIndex = derivationIndex;
    }

    /** @return provider-side address reference, if one was assigned */
    public String getProviderReference() {
        return providerReference;
    }

    /** @param providerReference identifier returned by the address or custody provider */
    public void setProviderReference(String providerReference) {
        this.providerReference = providerReference;
    }

    /** @return first transaction identifier observed for this address, if any */
    public String getFirstSeenTxid() {
        return firstSeenTxid;
    }

    /** @param firstSeenTxid transaction identifier from the first known address observation */
    public void setFirstSeenTxid(String firstSeenTxid) {
        this.firstSeenTxid = firstSeenTxid;
    }

    /** @return latest observation time, or {@code null} before the address is observed */
    public LocalDateTime getLastSeenAt() {
        return lastSeenAt;
    }

    /** @param lastSeenAt UTC time at which a provider or chain observer last saw the address */
    public void setLastSeenAt(LocalDateTime lastSeenAt) {
        this.lastSeenAt = lastSeenAt;
    }

    /** @return UTC insertion time for this address record */
    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    /** @return UTC retirement time, or {@code null} while the address has not been retired */
    public LocalDateTime getRetiredAt() {
        return retiredAt;
    }

    /** @param retiredAt UTC time at which the address ceased to be issued */
    public void setRetiredAt(LocalDateTime retiredAt) {
        this.retiredAt = retiredAt;
    }
}
