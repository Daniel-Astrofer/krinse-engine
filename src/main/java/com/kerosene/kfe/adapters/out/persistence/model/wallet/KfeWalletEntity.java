package com.kerosene.kfe.adapters.out.persistence.model.wallet;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import com.kerosene.common.persistence.StringCryptoConverter;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * Persists core ownership, policy, key material references, and lifecycle state for a KFE wallet.
 *
 * <p>The wallet belongs to one user and records its kind, status, asset, and spendability. HD
 * derivation metadata tracks address allocation, while extended public key and descriptor values
 * are protected by the configured crypto converter at persistence boundaries. The MPC public key
 * and quorum policy hash identify the signing context that must authorize spends. Lifecycle times
 * are captured in UTC.</p>
 */
@Entity
@Table(name = "wallets_core", schema = "financial", indexes = {
        @Index(name = "idx_wallets_core_user_created", columnList = "user_id, created_at"),
        @Index(name = "idx_wallets_core_kind_status", columnList = "kind, status")
})
public class KfeWalletEntity {

    /** Stable wallet UUID allocated before insertion. */
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id = UUID.randomUUID();

    /** User that owns the wallet and governs its access. */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** Wallet custody and control model, stored using its enum name. */
    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 32)
    private KfeWalletKind kind;

    /** Wallet operational state, initially creating until provisioning completes. */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private KfeWalletStatus status = KfeWalletStatus.CREATING;

    /** Required user-facing name used to distinguish this wallet in account views. */
    @Column(name = "label", nullable = false, length = 96)
    private String label;

    /** Asset managed by the wallet; defaults to Bitcoin (BTC). */
    @Column(name = "asset", nullable = false, length = 16)
    private String asset = "BTC";

    /** Whether this wallet can authorize outgoing operations under its current policy. */
    @Column(name = "spendable", nullable = false)
    private boolean spendable = true;

    /** Public key or aggregate key material used to identify the wallet's MPC signing key. */
    @Column(name = "mpc_public_key", columnDefinition = "TEXT")
    private String mpcPublicKey;

    /** Encrypted extended public key used to derive wallet addresses without storing private keys. */
    @Convert(converter = StringCryptoConverter.class)
    @Column(name = "xpub", columnDefinition = "TEXT")
    private String xpub;

    /** Encrypted output descriptor defining script type and public-key derivation for this wallet. */
    @Convert(converter = StringCryptoConverter.class)
    @Column(name = "descriptor", columnDefinition = "TEXT")
    private String descriptor;

    /** Key fingerprint used to identify the wallet's root public key in derivation metadata. */
    @Column(name = "fingerprint", length = 64)
    private String fingerprint;

    /** Base HD derivation path used to generate wallet addresses. */
    @Column(name = "derivation_path", length = 160)
    private String derivationPath;

    /** Highest derived child index recorded; {@code -1} means derivation has not started. */
    @Column(name = "last_derived_index", nullable = false)
    private int lastDerivedIndex = -1;

    /** Digest binding spend authorization to the quorum policy configured for this wallet. */
    @Column(name = "quorum_policy_hash", nullable = false, length = 64)
    private String quorumPolicyHash;

    /** Immutable UTC creation time of the wallet record. */
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /** UTC time of the most recent persisted wallet change. */
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /** Initializes creation and update timestamps from the same UTC instant before insertion. */
    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now(java.time.ZoneOffset.UTC);
        createdAt = now;
        updatedAt = now;
    }

    /** Refreshes the update timestamp in UTC before persisting a wallet change. */
    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now(java.time.ZoneOffset.UTC);
    }

    /** @return stable wallet UUID */
    public UUID getId() {
        return id;
    }

    /** @param id wallet UUID to assign, primarily for persistence or migration code */
    public void setId(UUID id) {
        this.id = id;
    }

    /** @return owning user's identifier */
    public Long getUserId() {
        return userId;
    }

    /** @param userId user identifier that owns the wallet */
    public void setUserId(Long userId) {
        this.userId = userId;
    }

    /** @return wallet custody/control category */
    public KfeWalletKind getKind() {
        return kind;
    }

    /** @param kind custody/control category to persist */
    public void setKind(KfeWalletKind kind) {
        this.kind = kind;
    }

    /** @return wallet provisioning and operational state */
    public KfeWalletStatus getStatus() {
        return status;
    }

    /** @param status provisioning or operational state to persist */
    public void setStatus(KfeWalletStatus status) {
        this.status = status;
    }

    /** @return user-facing wallet label */
    public String getLabel() {
        return label;
    }

    /** @param label required display name for the wallet */
    public void setLabel(String label) {
        this.label = label;
    }

    /** @return asset code managed by this wallet */
    public String getAsset() {
        return asset;
    }

    /** @param asset asset code to store, defaulting to BTC for new wallets */
    public void setAsset(String asset) {
        this.asset = asset;
    }

    /** @return whether outgoing spending is enabled for this wallet */
    public boolean isSpendable() {
        return spendable;
    }

    /** @param spendable {@code true} to allow spend operations subject to wallet policy */
    public void setSpendable(boolean spendable) {
        this.spendable = spendable;
    }

    /** @return aggregate or MPC public key identifying the wallet's signing key */
    public String getMpcPublicKey() {
        return mpcPublicKey;
    }

    /** @param mpcPublicKey public signing key or aggregate-key representation */
    public void setMpcPublicKey(String mpcPublicKey) {
        this.mpcPublicKey = mpcPublicKey;
    }

    /** @return decrypted extended public key supplied by the persistence converter */
    public String getXpub() {
        return xpub;
    }

    /** @param xpub extended public key; persistence encrypts it through the mapped converter */
    public void setXpub(String xpub) {
        this.xpub = xpub;
    }

    /** @return decrypted wallet output descriptor supplied by the persistence converter */
    public String getDescriptor() {
        return descriptor;
    }

    /** @param descriptor output descriptor; persistence encrypts it through the mapped converter */
    public void setDescriptor(String descriptor) {
        this.descriptor = descriptor;
    }

    /** @return root-key fingerprint associated with this wallet, if recorded */
    public String getFingerprint() {
        return fingerprint;
    }

    /** @param fingerprint root-key fingerprint used in derivation and signer identification */
    public void setFingerprint(String fingerprint) {
        this.fingerprint = fingerprint;
    }

    /** @return base derivation path used for wallet address generation */
    public String getDerivationPath() {
        return derivationPath;
    }

    /** @param derivationPath HD derivation path associated with the wallet's key */
    public void setDerivationPath(String derivationPath) {
        this.derivationPath = derivationPath;
    }

    /** @return highest child index already allocated, or {@code -1} before the first derivation */
    public int getLastDerivedIndex() {
        return lastDerivedIndex;
    }

    /** @param lastDerivedIndex latest allocated child index; coordinate updates to avoid address reuse */
    public void setLastDerivedIndex(int lastDerivedIndex) {
        this.lastDerivedIndex = lastDerivedIndex;
    }

    /** @return digest of the quorum policy required to authorize spending */
    public String getQuorumPolicyHash() {
        return quorumPolicyHash;
    }

    /** @param quorumPolicyHash digest binding wallet operations to a specific quorum policy */
    public void setQuorumPolicyHash(String quorumPolicyHash) {
        this.quorumPolicyHash = quorumPolicyHash;
    }

    /** @return immutable UTC wallet creation time */
    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    /** @return UTC time of the latest wallet update */
    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }
}
