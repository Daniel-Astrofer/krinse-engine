package com.kerosene.kfe.adapters.out.persistence.model.ledger;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/** Composite persistence key that scopes one balance row to a wallet and asset ticker. */
@Embeddable
public class KfeBalanceId implements Serializable {

    /** Wallet whose ledger balance is identified by this key. */
    @Column(name = "wallet_id", nullable = false)
    private UUID walletId;

    /** Asset ticker for the balance; BTC is used whenever construction receives null. */
    @Column(name = "asset", nullable = false, length = 16)
    private String asset = "BTC";

    /** Required by JPA when hydrating an embedded identifier from a database row. */
    public KfeBalanceId() {
    }

    /**
     * Creates the wallet/asset key and defaults a null asset to BTC.
     *
     * @param walletId wallet identifier
     * @param asset asset ticker; null selects BTC
     */
    public KfeBalanceId(UUID walletId, String asset) {
        this.walletId = walletId;
        this.asset = asset != null ? asset : "BTC";
    }

    /** @return wallet identifier that owns the balance */
    public UUID getWalletId() {
        return walletId;
    }

    /** @param walletId wallet identifier to associate with this balance key */
    public void setWalletId(UUID walletId) {
        this.walletId = walletId;
    }

    /** @return asset ticker identifying the denomination of the balance */
    public String getAsset() {
        return asset;
    }

    /** @param asset asset ticker to associate with this balance key */
    public void setAsset(String asset) {
        this.asset = asset;
    }

    /** Compares both wallet and asset components, as required for embedded-ID identity semantics. */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof KfeBalanceId that)) {
            return false;
        }
        return Objects.equals(walletId, that.walletId) && Objects.equals(asset, that.asset);
    }

    /** Hashes both key components so equal IDs share a stable map/set bucket. */
    @Override
    public int hashCode() {
        return Objects.hash(walletId, asset);
    }
}
