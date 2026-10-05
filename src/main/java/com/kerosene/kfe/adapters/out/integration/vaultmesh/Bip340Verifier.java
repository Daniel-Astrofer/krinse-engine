package com.kerosene.kfe.adapters.out.integration.vaultmesh;

import org.bouncycastle.asn1.x9.X9ECParameters;
import org.bouncycastle.crypto.ec.CustomNamedCurves;
import org.bouncycastle.math.ec.ECPoint;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

/** Verifies 32-byte-message BIP-340 Schnorr signatures over secp256k1 x-only public keys. */
final class Bip340Verifier {

    /** Bouncy Castle secp256k1 parameters used for point decoding and generator multiplication. */
    private static final X9ECParameters CURVE = CustomNamedCurves.getByName("secp256k1");
    /** Prime modulus of the secp256k1 base field, used to range-check x and signature r values. */
    private static final BigInteger FIELD_P =
            new BigInteger("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEFFFFFC2F", 16);
    /** Prime order of the secp256k1 generator, used to range-check s and reduce challenges. */
    private static final BigInteger ORDER_N = CURVE.getN();

    /** Prevents construction because signature verification is exposed as a stateless operation. */
    private Bip340Verifier() {
    }

    /**
     * Checks a BIP-340 signature by reconstructing {@code R = sG - eP} and verifying that R is
     * non-infinite, has even Y, and has X equal to the signature's r component. Message length and
     * scalar/field ranges are validated before curve arithmetic; malformed points and arithmetic
     * failures return false rather than escaping as runtime exceptions.
     *
     * @param message32 exact 32-byte message digest covered by the signature
     * @param publicKey 32-byte x-only key or 33-byte compressed secp256k1 key
     * @param signature64 64-byte Schnorr signature encoded as 32-byte r followed by 32-byte s
     * @return true only when the signature verifies under the normalized even-Y x-only public key
     */
    static boolean verify(byte[] message32, byte[] publicKey, byte[] signature64) {
        if (message32 == null || message32.length != 32
                || signature64 == null || signature64.length != 64) {
            return false;
        }
        byte[] xOnly = normalizeXOnly(publicKey);
        if (xOnly == null) {
            return false;
        }
        BigInteger px = new BigInteger(1, xOnly);
        BigInteger r = new BigInteger(1, Arrays.copyOfRange(signature64, 0, 32));
        BigInteger s = new BigInteger(1, Arrays.copyOfRange(signature64, 32, 64));
        if (px.compareTo(FIELD_P) >= 0 || r.compareTo(FIELD_P) >= 0 || s.compareTo(ORDER_N) >= 0) {
            return false;
        }
        try {
            byte[] compressed = new byte[33];
            compressed[0] = 0x02;
            System.arraycopy(xOnly, 0, compressed, 1, 32);
            ECPoint point = CURVE.getCurve().decodePoint(compressed).normalize();
            byte[] challengeInput = new byte[96];
            System.arraycopy(signature64, 0, challengeInput, 0, 32);
            System.arraycopy(xOnly, 0, challengeInput, 32, 32);
            System.arraycopy(message32, 0, challengeInput, 64, 32);
            BigInteger challenge = new BigInteger(1, taggedHash("BIP0340/challenge", challengeInput))
                    .mod(ORDER_N);
            ECPoint reconstructed = CURVE.getG().multiply(s)
                    .subtract(point.multiply(challenge))
                    .normalize();
            return !reconstructed.isInfinity()
                    && !reconstructed.getAffineYCoord().toBigInteger().testBit(0)
                    && reconstructed.getAffineXCoord().toBigInteger().equals(r);
        } catch (RuntimeException exception) {
            return false;
        }
    }

    /**
     * Converts an accepted x-only or compressed public-key representation to an owned 32-byte X coordinate.
     * Compressed-key parity is intentionally discarded because BIP-340 interprets x-only keys using
     * the even-Y lift; unsupported lengths and null input return null.
     *
     * @param key public key bytes supplied by the caller
     * @return cloned 32-byte X coordinate, or null when the encoding shape is unsupported
     */
    private static byte[] normalizeXOnly(byte[] key) {
        if (key == null) {
            return null;
        }
        if (key.length == 32) {
            return key.clone();
        }
        if (key.length == 33 && (key[0] == 0x02 || key[0] == 0x03)) {
            return Arrays.copyOfRange(key, 1, 33);
        }
        return null;
    }

    /**
     * Computes the BIP-340 tagged hash {@code SHA256(SHA256(tag) || SHA256(tag) || payload)}.
     * Tag bytes are encoded as US-ASCII, matching the standard's domain-separation construction.
     *
     * @param tag domain-separation label such as {@code BIP0340/challenge}
     * @param payload bytes covered by the tagged digest
     * @return 32-byte SHA-256 tagged digest
     * @throws IllegalStateException if the Java runtime does not provide SHA-256
     */
    private static byte[] taggedHash(String tag, byte[] payload) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] tagHash = digest.digest(tag.getBytes(StandardCharsets.US_ASCII));
            digest.reset();
            digest.update(tagHash);
            digest.update(tagHash);
            return digest.digest(payload);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }
}
