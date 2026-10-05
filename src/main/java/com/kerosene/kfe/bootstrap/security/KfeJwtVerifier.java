package com.kerosene.kfe.bootstrap.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Collection;
import java.util.Date;
import java.util.List;

/**
 * Verifies signed JWTs, enforces configured issuer/audience/expiration policy, checks revocation,
 * and extracts normalized role claims. A previous signing key is accepted only until its explicit
 * expiry; unavailable revocation dependencies are represented separately from invalid credentials.
 */
public class KfeJwtVerifier {

    /** Logger for missing replay identifiers; token values and claims are never logged. */
    private static final Logger log = LoggerFactory.getLogger(KfeJwtVerifier.class);

    /** Current HMAC verification key. */
    private final SecretKey secretKey;
    /** Optional previous HMAC key accepted during its configured rotation window. */
    private final SecretKey previousSecretKey;
    /** Exclusive end of the previous-key verification window. */
    private final Instant previousSecretExpiresAt;
    /** Optional Redis client used to check session revocation state. */
    private final StringRedisTemplate redisTemplate;
    /** Whether JWT session revocation lookups are attempted. */
    private final boolean revocationCheckEnabled;
    /** Whether missing/unavailable revocation state must fail authentication closed. */
    private final boolean revocationRequired;
    /** Optional issuer value required in accepted tokens. */
    private final String issuer;
    /** Optional audience value required in accepted tokens. */
    private final String audience;

    /**
     * Creates the verifier from application properties and an optional Redis dependency.
     *
     * @param secret current HMAC signing secret
     * @param redisTemplate provider for the optional Redis revocation client
     * @param revocationCheckEnabled whether to query revocation state
     * @param revocationRequired whether unavailable revocation state blocks authentication
     * @param issuer required token issuer, or blank to disable issuer matching
     * @param audience required token audience, or blank to disable audience matching
     * @param previousSecret optional previous HMAC key for rotation
     * @param previousSecretExpiresAt ISO-8601 expiry required when previousSecret is configured
     * @throws IllegalArgumentException for invalid keys or missing/invalid previous-key expiry
     */
    public KfeJwtVerifier(
            @Value("${api.secret.token.secret}") String secret,
            org.springframework.beans.factory.ObjectProvider<StringRedisTemplate> redisTemplate,
            @Value("${kfe.security.jwt.revocation-check-enabled:true}") boolean revocationCheckEnabled,
            @Value("${kfe.auth.revocation.required:false}") boolean revocationRequired,
            @Value("${kfe.auth.jwt.issuer:}") String issuer,
            @Value("${kfe.auth.jwt.audience:}") String audience,
            @Value("${api.secret.token.previous:}") String previousSecret,
            @Value("${api.secret.token.previous.expires-at:}") String previousSecretExpiresAt) {
        this(secret, redisTemplate.getIfAvailable(), revocationCheckEnabled, revocationRequired, issuer, audience,
                previousSecret, previousSecretExpiresAt);
    }

    /**
     * Creates a verifier without a previous-key rotation window.
     *
     * @param secret current HMAC signing secret
     * @param redisTemplate optional Redis revocation client
     * @param revocationCheckEnabled whether to query revocation state
     * @param revocationRequired whether unavailable revocation state blocks authentication
     * @param issuer required issuer, or blank to disable matching
     * @param audience required audience, or blank to disable matching
     */
    KfeJwtVerifier(String secret, StringRedisTemplate redisTemplate, boolean revocationCheckEnabled,
                   boolean revocationRequired, String issuer, String audience) {
        this(secret, redisTemplate, revocationCheckEnabled, revocationRequired, issuer, audience, "", "");
    }

    /**
     * Creates the fully configured verifier, validating key lengths and rotation expiry up front.
     *
     * @param secret current HMAC signing secret
     * @param redisTemplate optional Redis revocation client
     * @param revocationCheckEnabled whether to query revocation state
     * @param revocationRequired whether missing revocation proof is an availability failure
     * @param issuer required issuer, or blank to disable matching
     * @param audience required audience, or blank to disable matching
     * @param previousSecret optional old key accepted during migration
     * @param previousSecretExpiresAt ISO-8601 expiry for the old key
     * @throws IllegalArgumentException if configured keys are unusable or rotation has no expiry
     */
    KfeJwtVerifier(String secret, StringRedisTemplate redisTemplate, boolean revocationCheckEnabled,
                   boolean revocationRequired, String issuer, String audience,
                   String previousSecret, String previousSecretExpiresAt) {
        this.secretKey = key(secret, "api.secret.token.secret");
        String previous = blankToNull(previousSecret);
        this.previousSecretKey = previous == null ? null : key(previous, "api.secret.token.previous");
        this.previousSecretExpiresAt = previous == null ? null : parseExpiry(previousSecretExpiresAt);
        if (previous != null && this.previousSecretExpiresAt == null) {
            throw new IllegalArgumentException(
                    "api.secret.token.previous.expires-at is required when a previous JWT secret is configured");
        }
        this.redisTemplate = redisTemplate;
        this.revocationCheckEnabled = revocationCheckEnabled;
        this.revocationRequired = revocationRequired;
        this.issuer = blankToNull(issuer);
        this.audience = blankToNull(audience);
    }

    /**
     * Parses a token with the current key, falling back to the nonexpired previous key, then applies claims policy.
     * Expiration is mandatory; issuer and audience are enforced when configured; missing {@code jti} is warned.
     *
     * @param token compact signed JWT supplied by the caller
     * @return verified claims when signature, time, configured identity, and revocation checks pass
     * @throws JwtException if no active key verifies the token
     * @throws IllegalStateException if required claims fail or the session is revoked
     * @throws KfeAuthenticationUnavailableException if mandatory revocation proof is unavailable
     */
    public Claims verify(String token) {
        Claims claims;
        try {
            claims = parseSignedClaims(token, secretKey);
        } catch (JwtException currentFailure) {
            if (previousSecretKey == null || previousSecretExpiresAt.isBefore(Instant.now())) {
                throw currentFailure;
            }
            claims = parseSignedClaims(token, previousSecretKey);
        }

        // Require expiration claim
        Date expiration = claims.getExpiration();
        if (expiration == null) {
            throw new IllegalStateException("JWT is missing required 'exp' claim");
        }

        // Require issuer claim if configured
        if (issuer != null) {
            String tokenIssuer = claims.getIssuer();
            if (tokenIssuer == null || !issuer.equals(tokenIssuer)) {
                throw new IllegalStateException("JWT has invalid or missing 'iss' claim");
            }
        }

        // Require audience claim if configured
        if (audience != null) {
            Collection<String> audiences = claims.getAudience();
            if (audiences == null || audiences.isEmpty()
                    || audiences.stream().noneMatch(aud -> audience.equals(aud))) {
                throw new IllegalStateException("JWT has invalid or missing 'aud' claim");
            }
        }

        // Require jti if present (replay protection)
        if (claims.getId() == null) {
            log.warn("JWT is missing 'jti' claim; replay protection is not possible");
        }

        if (isRevoked(claims)) {
            throw new IllegalStateException("JWT session is revoked");
        }
        return claims;
    }

    /**
     * Extracts the {@code roles} collection, normalizes entries, removes duplicates, and defaults to USER.
     *
     * @param claims verified token claims
     * @return immutable normalized roles, or a single USER role when no usable roles exist
     */
    public List<String> roles(Claims claims) {
        Object rawRoles = claims.get("roles");
        if (rawRoles instanceof Collection<?> collection) {
            List<String> roles = collection.stream()
                    .map(String::valueOf)
                    .map(this::normalizeRole)
                    .filter(role -> !role.isBlank())
                    .distinct()
                    .toList();
            return roles.isEmpty() ? List.of("USER") : roles;
        }
        return List.of("USER");
    }

    /**
     * Checks the session revocation key when enabled and applies the configured fail-closed policy.
     *
     * @param claims verified claims containing the optional sessionId
     * @return true only when Redis confirms the session is revoked
     * @throws KfeAuthenticationUnavailableException when required revocation data cannot be confirmed
     */
    private boolean isRevoked(Claims claims) {
        if (!revocationCheckEnabled) {
            if (revocationRequired) {
                throw new KfeAuthenticationUnavailableException();
            }
            return false;
        }
        Object rawSessionId = claims.get("sessionId");
        if (rawSessionId == null || String.valueOf(rawSessionId).isBlank()) {
            if (revocationRequired) {
                throw new KfeAuthenticationUnavailableException();
            }
            return false;
        }
        if (redisTemplate == null) {
            if (revocationRequired) {
                throw new KfeAuthenticationUnavailableException();
            }
            return false;
        }
        final Boolean revoked;
        try {
            revoked = redisTemplate.hasKey("auth:jwt:revoked-session:" + rawSessionId);
        } catch (RuntimeException exception) {
            throw new KfeAuthenticationUnavailableException(exception);
        }
        if (revoked == null) {
            // No confirmed revocation result means access must remain closed.
            throw new KfeAuthenticationUnavailableException();
        }
        return revoked;
    }

    /**
     * Trims a role, uppercases it, and removes an existing Spring role prefix for canonical storage.
     *
     * @param role raw role claim value
     * @return canonical role name without {@code ROLE_}, or an empty string for null
     */
    private String normalizeRole(String role) {
        if (role == null) {
            return "";
        }
        String normalized = role.trim().toUpperCase();
        return normalized.startsWith("ROLE_") ? normalized.substring("ROLE_".length()) : normalized;
    }

    /**
     * Converts absent or whitespace-only configuration to null and trims all other values.
     *
     * @param value raw optional configuration value
     * @return trimmed nonblank value, or null
     */
    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /**
     * Verifies the JWS signature with one candidate key and returns its claims.
     *
     * @param token compact signed token
     * @param key candidate HMAC verification key
     * @return payload claims after signature verification
     * @throws JwtException if parsing or signature verification fails
     */
    private static Claims parseSignedClaims(String token, SecretKey key) {
        return Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
    }

    /**
     * Builds a JJWT HMAC key from UTF-8 secret bytes and attributes invalid-key errors to the property.
     *
     * @param secret configured secret material
     * @param property property name used in sanitized validation feedback
     * @return validated HMAC signing key
     * @throws IllegalArgumentException if the secret is not a usable HMAC key
     */
    private static SecretKey key(String secret, String property) {
        try {
            return io.jsonwebtoken.security.Keys.hmacShaKeyFor(secret.trim().getBytes(StandardCharsets.UTF_8));
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException(property + " must be a valid HMAC key", exception);
        }
    }

    /**
     * Parses an optional ISO-8601 instant after trimming blank configuration.
     *
     * @param value configured expiry text
     * @return parsed expiry, or null when absent/blank
     * @throws java.time.format.DateTimeParseException if a nonblank value is malformed
     */
    private static Instant parseExpiry(String value) {
        String normalized = blankToNull(value);
        return normalized == null ? null : Instant.parse(normalized);
    }
}
