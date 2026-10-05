package com.kerosene.kfe.adapters.in.http;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.TestingAuthenticationToken;
import com.kerosene.common.exception.ErrorCodes;
import com.kerosene.common.exception.StructuredPlatformException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class KfeAuthenticationSupportTest {

    @Test
    void returnsNumericAuthenticatedUserId() {
        Long userId = KfeAuthenticationSupport.authenticatedUserId(
                new TestingAuthenticationToken("42", "credentials"));

        assertEquals(42L, userId);
    }

    @Test
    void rejectsMissingAuthenticationAsUnauthorized() {
        StructuredPlatformException exception = assertThrows(
                StructuredPlatformException.class,
                () -> KfeAuthenticationSupport.authenticatedUserId(null));

        assertUnauthenticated(exception);
    }

    @Test
    void rejectsAnonymousAuthenticationAsUnauthorized() {
        StructuredPlatformException exception = assertThrows(
                StructuredPlatformException.class,
                () -> KfeAuthenticationSupport.authenticatedUserId(
                        new TestingAuthenticationToken("anonymousUser", "credentials")));

        assertUnauthenticated(exception);
    }

    @Test
    void rejectsNonNumericPrincipalAsUnauthorized() {
        StructuredPlatformException exception = assertThrows(
                StructuredPlatformException.class,
                () -> KfeAuthenticationSupport.authenticatedUserId(
                        new TestingAuthenticationToken("api-client", "credentials")));

        assertUnauthenticated(exception);
    }

    private void assertUnauthenticated(StructuredPlatformException exception) {
        assertEquals(HttpStatus.UNAUTHORIZED, exception.getStatus());
        assertEquals(ErrorCodes.AUTH_INVALID_CREDENTIALS, exception.getErrorCode());
    }
}
