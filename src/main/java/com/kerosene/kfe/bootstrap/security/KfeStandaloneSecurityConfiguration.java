package com.kerosene.kfe.bootstrap.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

/**
 * Configures stateless HTTP security, JWT verification, and explicit-origin CORS for standalone KFE.
 * Public health/payment-request routes and internal routes follow their own authorization contract;
 * administrative routes require ADMIN and unmatched routes are denied.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@ConditionalOnProperty(name = "kfe.standalone", havingValue = "true")
public class KfeStandaloneSecurityConfiguration {

    /**
     * Builds the stateless filter chain, route authorization rules, CORS integration, and JWT filter placement.
     * CSRF is disabled for this stateless bearer-token API; internal controller endpoints retain their
     * separate credential checks.
     *
     * @param http Spring Security HTTP builder
     * @param jwtAuthenticationFilter bearer token authentication filter
     * @param corsConfigurationSource explicit-origin CORS policy
     * @return configured stateless chain
     * @throws Exception if Spring Security cannot build the filter chain
     */
    @Bean
    public SecurityFilterChain kfeSecurityFilterChain(
            HttpSecurity http,
            KfeJwtAuthenticationFilter jwtAuthenticationFilter,
            CorsConfigurationSource corsConfigurationSource) throws Exception {
        http
                .cors(cors -> cors.configurationSource(corsConfigurationSource))
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(
                                "/",
                                "/healthz",
                                "/health/live",
                                "/health/ready",
                                "/health/dependencies",
                                "/actuator/health",
                                "/actuator/health/**",
                                "/api/public/kfe/**",
                                "/error")
                        .permitAll()
                        // The workload-identity filter authenticates internal routes before Spring Security.
                        .requestMatchers("/internal/kfe/**").permitAll()
                        .requestMatchers("/api/admin/kfe/**").hasRole("ADMIN")
                        .requestMatchers("/kfe/**").authenticated()
                        .anyRequest().denyAll())
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    /**
     * Creates the JWT verifier with standalone defaults for Redis revocation, issuer, and audience.
     * Previous-key verification remains bounded by its separately configured expiry.
     *
     * @param secret current JWT HMAC secret
     * @param redisTemplate optional Redis template provider
     * @param revocationCheckEnabled whether to query session revocation
     * @param revocationRequired whether absent/unavailable revocation proof fails closed
     * @param issuer required token issuer
     * @param audience required token audience
     * @param previousSecret optional key retained during rotation
     * @param previousSecretExpiresAt ISO-8601 expiration for the previous key
     * @return verifier configured for the standalone security policy
     */
    @Bean
    public KfeJwtVerifier kfeJwtVerifier(
            @Value("${api.secret.token.secret}") String secret,
            ObjectProvider<StringRedisTemplate> redisTemplate,
            @Value("${kfe.security.jwt.revocation-check-enabled:true}") boolean revocationCheckEnabled,
            @Value("${kfe.auth.revocation.required:true}") boolean revocationRequired,
            @Value("${kfe.auth.jwt.issuer:Kerosene-Auth}") String issuer,
            @Value("${kfe.auth.jwt.audience:kerosene-app}") String audience,
            @Value("${api.secret.token.previous:}") String previousSecret,
            @Value("${api.secret.token.previous.expires-at:}") String previousSecretExpiresAt) {
        return new KfeJwtVerifier(secret, redisTemplate.getIfAvailable(), revocationCheckEnabled,
                revocationRequired, issuer, audience, previousSecret, previousSecretExpiresAt);
    }

    /**
     * Creates the request filter that validates bearer tokens and populates Spring's security context.
     *
     * @param jwtVerifier configured signature and claims verifier
     * @return JWT authentication filter
     */
    @Bean
    public KfeJwtAuthenticationFilter kfeJwtAuthenticationFilter(
            KfeJwtVerifier jwtVerifier) {
        return new KfeJwtAuthenticationFilter(jwtVerifier);
    }

    /**
     * Builds a credentialed CORS policy from an explicit comma-separated origin allowlist.
     * Wildcards and empty lists are rejected because credentials are enabled. Methods, accepted headers,
     * and response headers are restricted to those used by the KFE clients.
     *
     * @param allowedOrigins comma-separated trusted browser origins
     * @return CORS source applied to all KFE routes
     * @throws IllegalStateException when the allowlist is empty or contains a wildcard
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource(
            @Value("${app.cors.allowed-origins:http://localhost:3000,http://localhost:3001,http://localhost:8080,http://localhost:8081,http://localhost:8082,http://localhost:30080,http://localhost:30082,http://127.0.0.1:3000,http://127.0.0.1:3001,http://127.0.0.1:8080,http://127.0.0.1:8081,http://127.0.0.1:8082,http://127.0.0.1:30080,http://127.0.0.1:30082}") String allowedOrigins) {
        List<String> origins = Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(origin -> !origin.isEmpty())
                .toList();
        if (origins.isEmpty() || origins.contains("*")) {
            throw new IllegalStateException("app.cors.allowed-origins must explicitly list trusted origins");
        }

        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(origins);
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of(
                "Authorization",
                "Content-Type",
                "Digest",
                "X-Correlation-Id",
                "X-Request-Id",
                "X-Requested-With",
                "X-Idempotency-Key",
                "Idempotency-Key",
                "X-Tx-Hash",
                "X-Device-Hash"));
        configuration.setExposedHeaders(List.of("X-Correlation-Id", "X-Request-Id"));
        configuration.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
