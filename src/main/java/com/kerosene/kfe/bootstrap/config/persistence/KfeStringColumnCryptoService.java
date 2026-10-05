package com.kerosene.kfe.bootstrap.config.persistence;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import com.kerosene.common.security.CryptoPurpose;
import com.kerosene.common.security.EncryptedValue;
import com.kerosene.common.security.StringColumnCryptoPort;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;

/**
 * AES-256-GCM column crypto for KFE standalone (xpub/descriptor at rest).
 *
 * <p>The main auth server uses {@code CosignerSecretService} + Vault. KFE is a
 * separate process and only scans {@code com.kerosene.kfe}, so it needs its own port
 * or {@link com.kerosene.common.persistence.StringCryptoConverter} fails with
 * "crypto port is not initialized".
 *
 * <p>Key resolution (first match):
 * <ol>
 *   <li>{@code KFE_COLUMN_CRYPTO_KEY_BASE64} — primary 32-byte AES key, base64</li>
 *   <li>SHA-256 of {@code KFE_INTERNAL_SHARED_SECRET} — only if
 *       {@code kfe.crypto.allow-shared-secret-derivation=true} (off by default)</li>
 * </ol>
 *
 * <p>Key rotation: set {@code KFE_COLUMN_CRYPTO_KEY_BASE64_V2} to a new key.
 * Decrypt tries v1 then v2; encrypt always uses the highest available version.
 */
@Service
public class KfeStringColumnCryptoService implements StringColumnCryptoPort {

    /** Logger that reports selected key source without logging key material. */
    private static final Logger log = LoggerFactory.getLogger(KfeStringColumnCryptoService.class);
    /** Required 96-bit nonce size for AES-GCM. */
    private static final int GCM_IV_LENGTH = 12;
    /** Authentication tag size used by the AES-GCM column format. */
    private static final int GCM_TAG_BITS = 128;

    /** Key used for new purpose-bound ciphertexts (the newest configured rotation key). */
    private final SecretKey masterKey;
    /** Ordered candidates accepted for decryption, retaining the primary key during rotation. */
    private final List<SecretKey> decryptKeys;
    /** Defensive byte snapshot returned by the legacy key-inspection port method. */
    private final byte[] masterKeyBytes;
    /** Per-instance cryptographically strong source of unique GCM nonces. */
    private final SecureRandom secureRandom = new SecureRandom();

    /**
     * Resolves the active encryption key and retained decryption keys from configuration.
     * A dedicated base64 256-bit key is preferred; deriving from the internal shared secret
     * requires an explicit opt-in and emits a warning because it couples authentication and data keys.
     *
     * @param keyBase64 primary dedicated AES-256 key, if configured
     * @param keyBase64V2 optional rotation key written by new encryption operations
     * @param sharedSecret source for the compatibility derivation fallback
     * @param allowSharedSecretDerivation whether the fallback key derivation is explicitly enabled
     * @throws IllegalStateException if no permitted key source is configured or a key is invalid
     */
    public KfeStringColumnCryptoService(
            @Value("${kfe.column-crypto.key-base64:}") String keyBase64,
            @Value("${kfe.column-crypto.key-base64-v2:}") String keyBase64V2,
            @Value("${kfe.internal.shared-secret:}") String sharedSecret,
            @Value("${kfe.crypto.allow-shared-secret-derivation:false}") boolean allowSharedSecretDerivation) {
        KeyResolutionResult resolution = resolveKeys(keyBase64, keyBase64V2, sharedSecret,
                allowSharedSecretDerivation);
        this.masterKey = resolution.primaryKey;
        this.decryptKeys = resolution.decryptKeys;
        this.masterKeyBytes = resolution.primaryKeyBytes;
        log.info("[KfeStringColumnCrypto] Initialized AES-256-GCM column crypto (key source: {}).",
                resolution.sourceDescription);
    }

    /**
     * Selects the write key and builds the candidate list used to read existing ciphertext.
     * The primary key is tried first, then V2; V2 is the write key whenever present.
     *
     * @param keyBase64 configured primary key text
     * @param keyBase64V2 configured rotation key text, or blank when rotation is inactive
     * @param sharedSecret optional compatibility derivation input
     * @param allowSharedSecretDerivation explicit switch for the compatibility input
     * @return resolved write key, decrypt candidates, key bytes, and non-secret source label
     */
    private static KeyResolutionResult resolveKeys(String keyBase64, String keyBase64V2,
                                                    String sharedSecret, boolean allowSharedSecretDerivation) {
        byte[] primaryBytes = null;
        byte[] v2Bytes = null;
        String sourceDesc;
        boolean fallback = false;

        if (keyBase64 != null && !keyBase64.isBlank()) {
            primaryBytes = decodeKey(keyBase64, "KFE_COLUMN_CRYPTO_KEY_BASE64");
            sourceDesc = "KFE_COLUMN_CRYPTO_KEY_BASE64";
        } else if (allowSharedSecretDerivation) {
            fallback = true;
            if (sharedSecret == null || sharedSecret.isBlank()) {
                throw new IllegalStateException(
                        "KFE column crypto: shared-secret derivation enabled but "
                                + "KFE_INTERNAL_SHARED_SECRET is not set.");
            }
            primaryBytes = deriveFromSharedSecret(sharedSecret);
            sourceDesc = "SHA-256(KFE_INTERNAL_SHARED_SECRET) [fallback]";
        } else {
            throw new IllegalStateException(
                    "KFE column crypto key (KFE_COLUMN_CRYPTO_KEY_BASE64) is not set and "
                            + "shared-secret derivation is disabled. "
                            + "Set KFE_COLUMN_CRYPTO_KEY_BASE64 or enable kfe.crypto.allow-shared-secret-derivation.");
        }

        if (fallback) {
            log.warn("KFE column crypto is deriving AES key from KFE_INTERNAL_SHARED_SECRET. "
                    + "This mixes service auth with data encryption. "
                    + "Set KFE_COLUMN_CRYPTO_KEY_BASE64 to a dedicated 32-byte base64 key.");
        }

        // Key versioning: V2 for rotation
        if (keyBase64V2 != null && !keyBase64V2.isBlank()) {
            v2Bytes = decodeKey(keyBase64V2, "KFE_COLUMN_CRYPTO_KEY_BASE64_V2");
            sourceDesc += " + V2 rotation key";
        }

        List<SecretKey> decryptKeyList = new ArrayList<>();
        decryptKeyList.add(new SecretKeySpec(primaryBytes, "AES"));
        if (v2Bytes != null) {
            decryptKeyList.add(new SecretKeySpec(v2Bytes, "AES"));
        }

        // Encrypt with latest (V2 if available, else V1)
        SecretKey encryptKey;
        if (v2Bytes != null) {
            encryptKey = new SecretKeySpec(v2Bytes, "AES");
        } else {
            encryptKey = new SecretKeySpec(primaryBytes, "AES");
        }

        return new KeyResolutionResult(encryptKey, decryptKeyList, encryptKey.getEncoded(), sourceDesc);
    }

    /**
     * Decodes and enforces an AES-256 key's exact 32-byte length.
     *
     * @param base64 base64-encoded key material
     * @param envVar configuration name included in validation errors
     * @return decoded key bytes
     * @throws IllegalStateException if the decoded key is not exactly 256 bits
     */
    private static byte[] decodeKey(String base64, String envVar) {
        byte[] decoded = Base64.getDecoder().decode(base64.trim());
        if (decoded.length != 32) {
            throw new IllegalStateException(
                    envVar + " must decode to exactly 32 bytes (AES-256), got " + decoded.length);
        }
        return decoded;
    }

    /**
     * Produces a fixed-length compatibility key by hashing the UTF-8 shared secret with SHA-256.
     *
     * @param sharedSecret configured internal service secret
     * @return 32-byte derived AES key
     * @throws IllegalStateException if SHA-256 is unavailable
     */
    private static byte[] deriveFromSharedSecret(String sharedSecret) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(sharedSecret.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to derive KFE column crypto key from shared secret", e);
        }
    }

    /**
     * Encrypts plaintext with purpose-bound AES-GCM and returns separately represented ciphertext/tag.
     * A fresh random nonce is generated for every call; purpose and nonempty associated data are
     * authenticated with a versioned domain prefix, so ciphertext cannot be reused across contexts.
     *
     * @param purpose semantic use that is bound into the authentication data
     * @param plaintext bytes to encrypt
     * @param associatedData nonempty record/context identity authenticated but not encrypted
     * @return encrypted payload, key identifier, algorithm metadata, nonce, tag, and creation time
     * @throws IllegalArgumentException if required values are null or associated data is empty
     * @throws IllegalStateException if the cryptographic provider fails
     */
    @Override
    public EncryptedValue encrypt(CryptoPurpose purpose, byte[] plaintext, byte[] associatedData) {
        if (purpose == null || plaintext == null || associatedData == null || associatedData.length == 0) {
            throw new IllegalArgumentException("Purpose, plaintext and non-empty associated data are required.");
        }
        try {
            byte[] nonce = new byte[GCM_IV_LENGTH];
            secureRandom.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, masterKey, new GCMParameterSpec(GCM_TAG_BITS, nonce));
            cipher.updateAAD(domainSeparatedAad(purpose, associatedData));
            byte[] sealed = cipher.doFinal(plaintext);
            int tagLength = GCM_TAG_BITS / Byte.SIZE;
            return new EncryptedValue(
                    keyId(masterKey),
                    "AES-256-GCM",
                    1,
                    nonce,
                    Arrays.copyOf(sealed, sealed.length - tagLength),
                    Arrays.copyOfRange(sealed, sealed.length - tagLength, sealed.length),
                    Instant.now());
        } catch (Exception exception) {
            throw new IllegalStateException("KFE purpose-bound encryption failed.", exception);
        }
    }

    /**
     * Decrypts a purpose-bound AES-GCM value using the key identified by its stable key ID.
     * Authentication failure, wrong purpose/context, and unavailable key IDs fail closed.
     *
     * @param purpose expected semantic use bound when the value was encrypted
     * @param encrypted metadata and ciphertext to authenticate and decrypt
     * @param associatedData nonempty record/context identity used during encryption
     * @return original plaintext bytes after successful GCM authentication
     * @throws IllegalArgumentException for missing context or unsupported format metadata
     * @throws IllegalStateException when the key is unavailable or authenticated decryption fails
     */
    @Override
    public byte[] decrypt(
            CryptoPurpose purpose,
            EncryptedValue encrypted,
            byte[] associatedData) {
        if (purpose == null || encrypted == null || associatedData == null || associatedData.length == 0) {
            throw new IllegalArgumentException("Purpose, encrypted value and non-empty associated data are required.");
        }
        if (!"AES-256-GCM".equals(encrypted.algorithm()) || encrypted.version() != 1) {
            throw new IllegalArgumentException("Unsupported KFE encrypted value algorithm or version.");
        }
        SecretKey key = decryptKeys.stream()
                .filter(candidate -> keyId(candidate).equals(encrypted.keyId()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "KFE encrypted value references an unavailable key version."));
        try {
            byte[] sealed = new byte[encrypted.ciphertext().length + encrypted.authenticationTag().length];
            System.arraycopy(encrypted.ciphertext(), 0, sealed, 0, encrypted.ciphertext().length);
            System.arraycopy(
                    encrypted.authenticationTag(),
                    0,
                    sealed,
                    encrypted.ciphertext().length,
                    encrypted.authenticationTag().length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, encrypted.nonce()));
            cipher.updateAAD(domainSeparatedAad(purpose, associatedData));
            return cipher.doFinal(sealed);
        } catch (Exception exception) {
            throw new IllegalStateException("KFE purpose-bound decryption failed.", exception);
        }
    }

    /**
     * Reports whether a value is encrypted with a key other than the current write key.
     *
     * @param value encrypted value to inspect
     * @return {@code true} for null values or values requiring rewrite under the active key
     */
    @Override
    public boolean needsRotation(EncryptedValue value) {
        return value == null || !keyId(masterKey).equals(value.keyId());
    }

    /**
     * Prefixes caller context with a stable application, purpose, and format-version namespace.
     *
     * @param purpose semantic encryption purpose
     * @param associatedData caller-provided context bytes
     * @return concatenated authentication-only data for GCM
     */
    private byte[] domainSeparatedAad(CryptoPurpose purpose, byte[] associatedData) {
        byte[] prefix = ("KEROSENE|" + purpose.name() + "|v1|").getBytes(StandardCharsets.UTF_8);
        byte[] result = new byte[prefix.length + associatedData.length];
        System.arraycopy(prefix, 0, result, 0, prefix.length);
        System.arraycopy(associatedData, 0, result, prefix.length, associatedData.length);
        return result;
    }

    /**
     * Derives a non-secret lookup identifier from the first eight bytes of the key's SHA-256 digest.
     *
     * @param key AES key whose stable identifier is required
     * @return prefixed lowercase hexadecimal key fingerprint
     * @throws IllegalStateException if the digest provider is unavailable
     */
    private String keyId(SecretKey key) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(key.getEncoded());
            return "kfe-column-" + java.util.HexFormat.of().formatHex(digest, 0, 8);
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to identify KFE column key.", exception);
        }
    }

    /**
     * Encrypts bytes using the legacy converter-compatible format: nonce followed by GCM output,
     * base64 encoded, without purpose-specific associated data or an explicit key identifier.
     *
     * @param plainBytes plaintext bytes supplied by the legacy string-column converter
     * @return base64 encoding of the 12-byte nonce followed by ciphertext and authentication tag
     * @throws IllegalStateException if encryption fails
     */
    @Override
    public String encrypt(byte[] plainBytes) {
        try {
            byte[] iv = new byte[GCM_IV_LENGTH];
            secureRandom.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, masterKey, new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] cipherText = cipher.doFinal(plainBytes);
            byte[] combined = new byte[iv.length + cipherText.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(cipherText, 0, combined, iv.length, cipherText.length);
            return Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            throw new IllegalStateException("KFE column encrypt failed", e);
        }
    }

    /**
     * Decrypts the legacy base64 nonce-plus-ciphertext format, trying configured keys in order.
     *
     * @param encryptedValue base64 encoded legacy value
     * @return decrypted plaintext bytes
     * @throws IllegalArgumentException if the value is invalid base64 or shorter than nonce plus data
     * @throws IllegalStateException if none of the retained keys authenticates the ciphertext
     */
    @Override
    public byte[] decrypt(String encryptedValue) {
        byte[] combined = Base64.getDecoder().decode(encryptedValue);
        if (combined.length <= GCM_IV_LENGTH) {
            throw new IllegalArgumentException("Ciphertext too short");
        }
        byte[] iv = Arrays.copyOfRange(combined, 0, GCM_IV_LENGTH);
        byte[] cipherText = Arrays.copyOfRange(combined, GCM_IV_LENGTH, combined.length);

        // Try each key for rotation support (v1 first, then v2)
        for (int i = 0; i < decryptKeys.size(); i++) {
            try {
                Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(Cipher.DECRYPT_MODE, decryptKeys.get(i),
                        new GCMParameterSpec(GCM_TAG_BITS, iv));
                return cipher.doFinal(cipherText);
            } catch (Exception e) {
                if (i == decryptKeys.size() - 1) {
                    throw new IllegalStateException(
                            "KFE column decrypt failed with all available keys", e);
                }
                // Try next key
            }
        }
        throw new IllegalStateException("KFE column decrypt failed: no keys available");
    }

    /**
     * Returns a defensive copy of the active write key bytes for the legacy inspection contract.
     *
     * @return copy of the currently selected 256-bit AES key
     */
    @Override
    public byte[] getMasterKeyBytes() {
        return Arrays.copyOf(masterKeyBytes, masterKeyBytes.length);
    }

    /**
     * Internal result of resolving the encryption key and rotation candidates.
     *
     * @param primaryKey key selected for new encryption operations
     * @param decryptKeys keys retained to decrypt stored values during rotation
     * @param primaryKeyBytes encoded bytes of the selected write key
     * @param sourceDescription human-readable key source label with no secret material
     */
    private record KeyResolutionResult(
            SecretKey primaryKey,
            List<SecretKey> decryptKeys,
            byte[] primaryKeyBytes,
            String sourceDescription) {
    }
}
