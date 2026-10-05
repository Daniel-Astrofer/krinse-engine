package com.kerosene.kfe.adapters.in.http;

import com.kerosene.kfe.adapters.out.integration.rail.KfeFinancialRailHealthAdapter;
import com.kerosene.kfe.bootstrap.runtime.KfeVaultMeshReadinessProbe;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.availability.ApplicationAvailability;
import org.springframework.boot.availability.ApplicationAvailabilityBean;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import javax.sql.DataSource;
import java.sql.Connection;
import com.kerosene.common.financial.operations.FinancialRailHealthPort;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoInteractions;

class KfeHealthControllerTest {

    private final DataSource dataSource = mock(DataSource.class);
    private final KfeFinancialRailHealthAdapter railHealth = mock(KfeFinancialRailHealthAdapter.class);
    private final ApplicationAvailability applicationAvailability = mock(ApplicationAvailability.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<DataSource> dataSourceProvider = mock(ObjectProvider.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<KfeFinancialRailHealthAdapter> railHealthProvider = mock(ObjectProvider.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<KfeVaultMeshReadinessProbe> vaultMeshReadinessProvider = mock(ObjectProvider.class);

    private KfeHealthController controller;

    @BeforeEach
    void setUp() {
        when(dataSourceProvider.getIfAvailable()).thenReturn(dataSource);
        when(railHealthProvider.getIfAvailable()).thenReturn(null);
        when(vaultMeshReadinessProvider.getIfAvailable()).thenReturn(null);
        when(applicationAvailability.getReadinessState()).thenReturn(ReadinessState.ACCEPTING_TRAFFIC);
        controller = new KfeHealthController(
                dataSourceProvider, railHealthProvider, vaultMeshReadinessProvider, applicationAvailability);
    }

    @Test
    void liveAlwaysReturnsUp() {
        when(applicationAvailability.getReadinessState()).thenReturn(ReadinessState.REFUSING_TRAFFIC);
        var snapshot = controller.live();
        assertThat(snapshot.status()).isEqualTo("UP");
        verifyNoInteractions(applicationAvailability, dataSourceProvider, railHealthProvider, vaultMeshReadinessProvider);
    }

    @Test
    void readyRefusesBeforeStartupCompletesWithoutQueryingDependencies() {
        when(applicationAvailability.getReadinessState()).thenReturn(ReadinessState.REFUSING_TRAFFIC);

        var response = controller.ready();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody().dependencies()).containsEntry("application", "REFUSING_TRAFFIC");
        verifyNoInteractions(dataSourceProvider, railHealthProvider, vaultMeshReadinessProvider);
    }

    @Test
    void readyRefusesUnknownApplicationAvailability() {
        when(applicationAvailability.getReadinessState()).thenReturn(null);
        assertThat(controller.ready().getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        verifyNoInteractions(dataSourceProvider, railHealthProvider, vaultMeshReadinessProvider);
    }

    @Test
    void readinessTracksBootAvailabilityFromStartupThroughShutdown() throws Exception {
        var availability = new ApplicationAvailabilityBean();
        var lifecycleController = new KfeHealthController(
                dataSourceProvider, railHealthProvider, vaultMeshReadinessProvider, availability);
        var connection = mock(Connection.class);
        when(connection.isValid(2)).thenReturn(true);
        when(dataSource.getConnection()).thenReturn(connection);

        assertThat(lifecycleController.ready().getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        verifyNoInteractions(dataSourceProvider, railHealthProvider, vaultMeshReadinessProvider);
        availability.onApplicationEvent(new AvailabilityChangeEvent<>(this, ReadinessState.ACCEPTING_TRAFFIC));
        assertThat(lifecycleController.ready().getStatusCode()).isEqualTo(HttpStatus.OK);
        availability.onApplicationEvent(new AvailabilityChangeEvent<>(this, ReadinessState.REFUSING_TRAFFIC));
        assertThat(lifecycleController.ready().getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(lifecycleController.live().status()).isEqualTo("UP");
    }

    @Test
    void readyReturnsUpWhenDatabaseUpAndNoRails() throws Exception {
        Connection conn = mock(Connection.class);
        when(conn.isValid(2)).thenReturn(true);
        when(dataSource.getConnection()).thenReturn(conn);

        ResponseEntity<KfeHealthController.KfeHealthSnapshot> response = controller.ready();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().status()).isEqualTo("UP");
        assertThat(response.getBody().dependencies()).containsKey("database");
    }

    @Test
    void readyReturnsDownWhenDatabaseDown() throws Exception {
        when(dataSource.getConnection()).thenThrow(new RuntimeException("DB down"));

        ResponseEntity<KfeHealthController.KfeHealthSnapshot> response = controller.ready();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody().status()).isEqualTo("DOWN");
    }

    @Test
    void readyReturnsDownWhenAllRailsDead() throws Exception {
        Connection conn = mock(Connection.class);
        when(conn.isValid(2)).thenReturn(true);
        when(dataSource.getConnection()).thenReturn(conn);
        when(railHealthProvider.getIfAvailable()).thenReturn(railHealth);
        when(railHealth.custodyProviderHealth()).thenReturn(
                new FinancialRailHealthPort.ProviderHealth(
                        "lnd", "LndRestLightningClient", FinancialRailHealthPort.HealthState.UNAVAILABLE,
                        false, false, false, false, "MAINNET", 0L, null, "DOWN"));
        when(railHealth.activeRailProviderHealth()).thenReturn(List.of());

        ResponseEntity<KfeHealthController.KfeHealthSnapshot> response = controller.ready();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody().status()).isEqualTo("DOWN");
    }

    @Test
    void readyReturnsUpWhenAtLeastOneRailLive() throws Exception {
        Connection conn = mock(Connection.class);
        when(conn.isValid(2)).thenReturn(true);
        when(dataSource.getConnection()).thenReturn(conn);
        when(railHealthProvider.getIfAvailable()).thenReturn(railHealth);
        when(railHealth.custodyProviderHealth()).thenReturn(
                new FinancialRailHealthPort.ProviderHealth(
                        "lnd", "LndRestLightningClient", FinancialRailHealthPort.HealthState.AVAILABLE,
                        true, true, true, true, "MAINNET", 0L, null, null));
        when(railHealth.activeRailProviderHealth()).thenReturn(List.of());

        ResponseEntity<KfeHealthController.KfeHealthSnapshot> response = controller.ready();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().status()).isEqualTo("UP");
        assertThat(response.getBody().dependencies()).containsKey("custody-lnd");
    }

    @Test
    void readyReturnsDownWhenVaultMeshProbeIsDown() throws Exception {
        Connection conn = mock(Connection.class);
        when(conn.isValid(2)).thenReturn(true);
        when(dataSource.getConnection()).thenReturn(conn);
        KfeVaultMeshReadinessProbe mesh = mock(KfeVaultMeshReadinessProbe.class);
        when(mesh.current()).thenReturn(new KfeVaultMeshReadinessProbe.Snapshot(
                false, "DOWN:CHANNELS_KEYSET_UNAVAILABLE", java.time.Instant.now()));
        when(vaultMeshReadinessProvider.getIfAvailable()).thenReturn(mesh);

        ResponseEntity<KfeHealthController.KfeHealthSnapshot> response = controller.ready();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody().dependencies())
                .containsEntry("vault-mesh", "DOWN:CHANNELS_KEYSET_UNAVAILABLE");
    }
}
