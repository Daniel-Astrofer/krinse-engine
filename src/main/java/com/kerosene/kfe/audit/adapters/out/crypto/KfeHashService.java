package com.kerosene.kfe.audit.adapters.out.crypto;

import org.springframework.stereotype.Service;
import com.kerosene.kfe.adapters.out.persistence.model.ledger.KfeBalanceEntity;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * Computes stable SHA-256 digests for audit and balance-integrity records.
 */
@Service
public class KfeHashService {

    /**
     * Hashes UTF-8 text and returns lowercase hexadecimal output.
     * Null input is normalized to the empty string.
     *
     * @param value text to hash
     * @return 64-character lowercase SHA-256 digest
     * @throws IllegalStateException when the runtime does not provide SHA-256
     */
    public String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest((value != null ? value : "")
                    .getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }

    /**
     * Creates the deterministic genesis digest for a wallet/asset balance chain.
     *
     * @param walletId wallet identifier
     * @param asset asset code
     * @return digest over the KFE balance-genesis domain and identifiers
     */
    public String initialBalanceHash(String walletId, String asset) {
        return sha256("KFE_BALANCE_GENESIS|" + walletId + "|" + asset);
    }

    /**
     * Hashes the balance buckets and nonce in a fixed field order for integrity verification.
     *
     * @param balance persisted balance entity whose current state is hashed
     * @return lowercase SHA-256 digest bound to wallet, asset, buckets, debt, and nonce
     */
    public String balanceHash(KfeBalanceEntity balance) {
        return sha256(String.join("|",
                "KFE_BALANCE",
                String.valueOf(balance.getId().getWalletId()),
                balance.getId().getAsset(),
                String.valueOf(balance.getAvailableSats()),
                String.valueOf(balance.getPendingSats()),
                String.valueOf(balance.getLockedSats()),
                String.valueOf(balance.getAutoHoldSats()),
                String.valueOf(balance.getObservedSats()),
                String.valueOf(balance.getReorgDebtSats()),
                String.valueOf(balance.getNonce())));
    }
}
