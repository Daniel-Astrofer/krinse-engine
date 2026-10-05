package com.kerosene.kfe.paymentexecution.adapters.in.http;

import com.kerosene.common.exception.ErrorCodes;
import com.kerosene.common.exception.StructuredPlatformException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;

/** Converts the authenticated Spring principal into the positive numeric owner ID used by KFE. */
final class AuthenticatedUserResolver {

    /** Prevents instances of this stateless principal-resolution utility. */
    private AuthenticatedUserResolver() {
    }

    /** Rejects missing, anonymous, malformed, and non-positive principals as unauthenticated. */
    static long userId(Authentication authentication) {
        if (authentication == null
                || !authentication.isAuthenticated()
                || authentication.getName() == null
                || "anonymousUser".equals(authentication.getName())) {
            throw unauthenticated();
        }
        try {
            long userId = Long.parseLong(authentication.getName());
            if (userId <= 0L) {
                throw unauthenticated();
            }
            return userId;
        } catch (NumberFormatException exception) {
            throw unauthenticated();
        }
    }

    /** Creates the standard structured HTTP 401 failure for an unusable principal. */
    private static StructuredPlatformException unauthenticated() {
        return new StructuredPlatformException(
                "Usuario autenticado e obrigatorio para operacoes KFE.",
                HttpStatus.UNAUTHORIZED,
                ErrorCodes.AUTH_INVALID_CREDENTIALS,
                null);
    }
}
