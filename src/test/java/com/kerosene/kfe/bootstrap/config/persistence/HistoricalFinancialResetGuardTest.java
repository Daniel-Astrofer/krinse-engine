package com.kerosene.kfe.bootstrap.config.persistence;

import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.callback.Context;
import org.flywaydb.core.api.callback.Event;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HistoricalFinancialResetGuardTest {
    private final HistoricalFinancialResetGuard guard = new HistoricalFinancialResetGuard();

    @ParameterizedTest
    @ValueSource(strings = {
            "V14__kfe_legacy_financial_expurge.sql",
            "V23__drop_legacy_financial_tables.sql",
            "V27__remove_mining_marketplace_tables.sql"
    })
    void rejectsPendingHistoricalResets(String script) {
        Context context = context(script);
        assertThatThrownBy(() -> guard.handle(Event.BEFORE_EACH_MIGRATE, context))
                .isInstanceOf(FlywayException.class)
                .hasMessageContaining(script);
    }

    @Test
    void permitsNormalForwardMigrationsAndDoesNotInterfereWithValidation() {
        Context context = context("V60__ledger_append_only_and_amount_invariants.sql");
        assertThat(guard.supports(Event.BEFORE_EACH_MIGRATE, context)).isTrue();
        assertThat(guard.supports(Event.BEFORE_VALIDATE, context)).isFalse();
        assertThatCode(() -> guard.handle(Event.BEFORE_EACH_MIGRATE, context)).doesNotThrowAnyException();
    }

    private Context context(String script) {
        Context context = mock(Context.class);
        MigrationInfo migration = mock(MigrationInfo.class);
        when(context.getMigrationInfo()).thenReturn(migration);
        when(migration.getScript()).thenReturn(script);
        return context;
    }
}
