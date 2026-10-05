package com.kerosene.kfe.adapters.in.http;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import com.kerosene.common.exception.ErrorCodes;
import com.kerosene.common.exception.StructuredPlatformException;

/** Shared conversion of Spring Security authentication into the numeric KFE user identity. */
public final class KfeAuthenticationSupport {

    /** Prevents construction because this type exposes only stateless authentication helpers. */
    private KfeAuthenticationSupport() {
    }

    /**
     * Extracts a numeric user ID from the authenticated principal name.
     * Null, anonymous, missing, or nonnumeric principals are rejected uniformly.
     *
     * @param authentication Spring Security authentication supplied by the request context
     * @return the principal name parsed as a database user ID
     * @throws StructuredPlatformException when the request has no valid authenticated user
     */
    public static Long authenticatedUserId(Authentication authentication) {
        if (authentication == null
                || authentication.getName() == null
                || "anonymousUser".equals(authentication.getName())) {
            throw unauthenticated();
        }
        try {
            return Long.parseLong(authentication.getName());
        } catch (NumberFormatException exception) {
            throw unauthenticated();
        }
    }

    /** @return the standardized unauthorized exception used for absent or invalid KFE principals */
    public static StructuredPlatformException unauthenticated() {
        return new StructuredPlatformException(
                "Usuario autenticado e obrigatorio para operacoes KFE.",
                HttpStatus.UNAUTHORIZED,
                ErrorCodes.AUTH_INVALID_CREDENTIALS,
                null);
    }
}
