package com.kerosene.kfe.bootstrap.config.persistence;

import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.callback.Callback;
import org.flywaydb.core.api.callback.Context;
import org.flywaydb.core.api.callback.Event;
import org.springframework.stereotype.Component;

import java.util.Set;

/** Retains immutable migration history without allowing historical data resets to run again. */
@Component
public final class HistoricalFinancialResetGuard implements Callback {
    private static final Set<String> RESET_SCRIPTS = Set.of(
            "V14__kfe_legacy_financial_expurge.sql",
            "V23__drop_legacy_financial_tables.sql",
            "V27__remove_mining_marketplace_tables.sql");

    @Override
    public boolean supports(Event event, Context context) {
        return event == Event.BEFORE_EACH_MIGRATE;
    }

    @Override
    public boolean canHandleInTransaction(Event event, Context context) {
        return true;
    }

    @Override
    public void handle(Event event, Context context) {
        if (event == Event.BEFORE_EACH_MIGRATE
                && RESET_SCRIPTS.contains(context.getMigrationInfo().getScript())) {
            throw new FlywayException("Historical financial reset is not an application startup operation: "
                    + context.getMigrationInfo().getScript()
                    + ". Recover the verified database history or use an approved bootstrap migration.");
        }
    }

    @Override
    public String getCallbackName() {
        return "historical-financial-reset-guard";
    }
}
