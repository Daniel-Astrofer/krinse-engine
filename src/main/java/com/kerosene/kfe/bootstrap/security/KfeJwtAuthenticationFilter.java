package com.kerosene.kfe.bootstrap.security;

import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Extracts bearer JWTs, verifies identity and roles, and installs authenticated principals in Spring Security.
 * Missing bearer credentials pass through for downstream authorization; invalid credentials clear the
 * context and return 401, while unavailable verification dependencies return 503.
 */
public class KfeJwtAuthenticationFilter extends OncePerRequestFilter {

    /** Logger that records rejection categories without token contents or claims. */
    private static final Logger logger = LoggerFactory.getLogger(KfeJwtAuthenticationFilter.class);
    /** Verifier responsible for signature/claim checks and normalized role extraction. */
    private final KfeJwtVerifier jwtVerifier;

    /**
     * Creates the filter with the configured token verifier.
     *
     * @param jwtVerifier verifier for bearer token signatures, claims, and role values
     */
    public KfeJwtAuthenticationFilter(KfeJwtVerifier jwtVerifier) {
        this.jwtVerifier = jwtVerifier;
    }

    /**
     * Processes bearer credentials once per request and delegates when authentication succeeds or is absent.
     * Runtime verification failures map to invalid-session responses; infrastructure failures map to a
     * temporary-unavailable response. Neither path exposes exception messages or token material.
     *
     * @param request current servlet request
     * @param response response to update for rejected or temporarily unverifiable credentials
     * @param filterChain remaining Spring Security filters and endpoint handling
     * @throws ServletException if downstream filter processing fails
     * @throws IOException if reading the request or writing the error response fails
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String authorization = request.getHeader("Authorization");
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            filterChain.doFilter(request, response);
            return;
        }

        try {
            Claims claims = jwtVerifier.verify(authorization.substring("Bearer ".length()));
            Long userId = Long.parseLong(claims.getId());
            List<SimpleGrantedAuthority> authorities = jwtVerifier.roles(claims).stream()
                    .map(KfeJwtAuthenticationFilter::authority)
                    .distinct()
                    .toList();
            SecurityContextHolder.getContext()
                    .setAuthentication(new UsernamePasswordAuthenticationToken(userId, null, authorities));
        } catch (KfeAuthenticationUnavailableException exception) {
            logger.warn("[KFE JWT] Authentication dependency unavailable");
            SecurityContextHolder.clearContext();
            response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            response.setContentType("application/json");
            response.getWriter().write("""
                    {"success":false,"message":"Authentication temporarily unavailable","errorCode":"SYS_500"}
                    """);
            return;
        } catch (RuntimeException exception) {
            logger.warn("[KFE JWT] Authentication rejected exceptionClass={}", exception.getClass().getSimpleName());
            SecurityContextHolder.clearContext();
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json");
            response.getWriter().write("""
                    {"success":false,"message":"invalid session","errorCode":"INVALID_SESSION"}
                    """);
            return;
        }
        // Controller/service failures are not invalid credentials and must not log the user out.
        filterChain.doFilter(request, response);
    }

    /**
     * Converts a role claim to Spring's {@code ROLE_} authority convention.
     * Null roles default to USER; surrounding whitespace and input case are normalized.
     *
     * @param role role claim value, possibly null
     * @return normalized authority with exactly the required prefix
     */
    private static SimpleGrantedAuthority authority(String role) {
        String normalized = role == null ? "USER" : role.trim().toUpperCase();
        if (!normalized.startsWith("ROLE_")) {
            normalized = "ROLE_" + normalized;
        }
        return new SimpleGrantedAuthority(normalized);
    }
}
