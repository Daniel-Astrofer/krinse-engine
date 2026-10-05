package com.kerosene.kfe.adapters.in.http;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.availability.ApplicationAvailability;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import com.kerosene.kfe.adapters.out.integration.rail.KfeFinancialRailHealthAdapter;
import com.kerosene.kfe.bootstrap.runtime.KfeVaultMeshReadinessProbe;
import com.kerosene.common.financial.operations.FinancialRailHealthPort;

import javax.sql.DataSource;
import java.sql.Connection;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Exposes process liveness and dependency readiness endpoints for standalone KFE deployments.
 * Readiness remains unavailable until the application accepts traffic and the configured
 * database, financial rails, and optional vault mesh satisfy their health checks.
 */
@RestController
@ConditionalOnProperty(name = "kfe.standalone", havingValue = "true")
public class KfeHealthController {

    /** Optional JDBC connection source; absence is reported as not configured. */
    private final ObjectProvider<DataSource> dataSource;
    /** Optional adapter that reports custody and Lightning provider health. */
    private final ObjectProvider<KfeFinancialRailHealthAdapter> railHealth;
    /** Optional probe for authenticated vault mesh readiness. */
    private final ObjectProvider<KfeVaultMeshReadinessProbe> vaultMeshReadiness;
    /** Spring lifecycle state used to prevent traffic during application startup. */
    private final ApplicationAvailability applicationAvailability;

    /**
     * Creates the health controller with optional dependency probes.
     * @param dataSource provider for database connectivity checks
     * @param railHealth provider for financial rail health checks
     * @param vaultMeshReadiness provider for vault mesh readiness
     * @param applicationAvailability lifecycle state supplied by Spring Boot
     */
    public KfeHealthController(
            ObjectProvider<DataSource> dataSource,
            ObjectProvider<KfeFinancialRailHealthAdapter> railHealth,
            ObjectProvider<KfeVaultMeshReadinessProbe> vaultMeshReadiness,
            ApplicationAvailability applicationAvailability) {
        this.dataSource = dataSource;
        this.railHealth = railHealth;
        this.vaultMeshReadiness = vaultMeshReadiness;
        this.applicationAvailability = Objects.requireNonNull(applicationAvailability);
    }

    /**
     * Reports process liveness without waiting for application dependencies.
     * @return an {@code UP} snapshot indicating the HTTP process can respond
     */
    @GetMapping({"/healthz", "/health/live"})
    public KfeHealthSnapshot live() {
        return new KfeHealthSnapshot("UP", "kfe-service", Instant.now(), Map.of());
    }

    /**
     * Reports startup and dependency readiness for traffic routing.
     * A missing database is treated as not configured; when rails are configured,
     * at least one must be live, and an available vault mesh probe must be ready.
     * @return HTTP 200 with an {@code UP} snapshot, or 503 with a {@code DOWN} snapshot
     */
    @GetMapping({"/health/ready", "/health/dependencies"})
    public ResponseEntity<KfeHealthSnapshot> ready() {
        if (applicationAvailability.getReadinessState() != ReadinessState.ACCEPTING_TRAFFIC) {
            // HTTP liveness may start before runners finish; never route traffic during bootstrap.
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(new KfeHealthSnapshot(
                    "DOWN", "kfe-service", Instant.now(), Map.of("application", "REFUSING_TRAFFIC")));
        }
        DependencyStatus database = databaseStatus();
        Map<String, String> dependencies = new LinkedHashMap<>();
        dependencies.put("application", "UP");
        dependencies.put("database", database.status());

        // Add rail health: readiness requires at least one custody or Lightning rail live
        KfeFinancialRailHealthAdapter rails = railHealth.getIfAvailable();
        boolean anyRailLive = false;
        if (rails != null) {
            var custody = rails.custodyProviderHealth();
            if (custody != null) {
                boolean live = custody.state() == FinancialRailHealthPort.HealthState.AVAILABLE;
                String custodyStatus = live ? "UP" : "DOWN";
                dependencies.put("custody-" + custody.providerName().toLowerCase(), custodyStatus);
                if (live) anyRailLive = true;
            }
            var activeRails = rails.activeRailProviderHealth();
            for (var rail : activeRails) {
                boolean live = rail.state() == FinancialRailHealthPort.HealthState.AVAILABLE;
                String railStatus = live ? "UP" : "DOWN";
                dependencies.put("rail-" + rail.providerName(), railStatus);
                if (live) anyRailLive = true;
            }
        }

        // Ready if DB is up and at least one financial rail is live (or no rails configured)
        boolean railOk = rails == null || anyRailLive;
        KfeVaultMeshReadinessProbe mesh = vaultMeshReadiness.getIfAvailable();
        boolean vaultMeshOk = true;
        if (mesh != null) {
            KfeVaultMeshReadinessProbe.Snapshot vault = mesh.current();
            dependencies.put("vault-mesh", vault.status());
            vaultMeshOk = vault.ready();
        }
        boolean up = database.up() && railOk && vaultMeshOk;
        HttpStatus status = up ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE;
        return ResponseEntity.status(status).body(new KfeHealthSnapshot(
                up ? "UP" : "DOWN",
                "kfe-service",
                Instant.now(),
                Map.copyOf(dependencies)));
    }

    /**
     * Checks database connection validity using the configured datasource.
     * @return a status marked {@code not-configured} when no datasource exists,
     *         {@code UP} when a connection is valid, or {@code DOWN} on failure
     */
    private DependencyStatus databaseStatus() {
        DataSource availableDataSource = dataSource.getIfAvailable();
        if (availableDataSource == null) {
            return new DependencyStatus(true, "not-configured");
        }
        try (Connection connection = availableDataSource.getConnection()) {
            boolean valid = connection.isValid(2);
            return new DependencyStatus(valid, valid ? "UP" : "DOWN");
        } catch (Exception exception) {
            return new DependencyStatus(false, "DOWN");
        }
    }

    /**
     * Public response body shared by liveness and readiness endpoints.
     * @param status overall service state, either {@code UP} or {@code DOWN}
     * @param service stable service identifier
     * @param checkedAt instant at which the response snapshot was created
     * @param dependencies per-dependency state labels included in readiness responses
     */
    public record KfeHealthSnapshot(
            String status,
            String service,
            Instant checkedAt,
            Map<String, String> dependencies) {
    }

    /**
     * Internal pair of database availability and its externally reported status label.
     * @param up whether the datasource is usable for readiness
     * @param status state label included in the response map
     */
    private record DependencyStatus(
            boolean up,
            String status) {
    }
}
