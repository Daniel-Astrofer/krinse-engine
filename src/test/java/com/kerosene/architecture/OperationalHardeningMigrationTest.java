package com.kerosene.architecture;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class OperationalHardeningMigrationTest {

    private static final Path PROJECT_ROOT = Path.of("").toAbsolutePath();
    private static final Path V2_MIGRATION = PROJECT_ROOT.resolve("src/main/resources/db/migration/V2__operational_hardening.sql");

    @Test
    void v2MigrationMaterializesItsBaseTablesBeforeAlteringThem() throws IOException {
        String migration = Files.readString(V2_MIGRATION);

        assertTrue(migration.contains("CREATE TABLE IF NOT EXISTS financial.wallets"));
        assertTrue(migration.contains("CREATE TABLE IF NOT EXISTS financial.ledger"));
        assertTrue(migration.contains("CREATE TABLE IF NOT EXISTS financial.ledger_transaction_history"));
        assertAppearsBefore(migration,
                "CREATE TABLE IF NOT EXISTS financial.ledger",
                "CREATE INDEX IF NOT EXISTS idx_ledger_wallet_id");
        assertAppearsBefore(migration,
                "CREATE TABLE IF NOT EXISTS financial.ledger_transaction_history",
                "CREATE INDEX IF NOT EXISTS idx_ledger_history_receiver");
    }

    private static void assertAppearsBefore(String text, String earlier, String later) {
        int earlierIndex = text.indexOf(earlier);
        int laterIndex = text.indexOf(later);

        assertTrue(earlierIndex >= 0, () -> "Missing expected SQL: " + earlier);
        assertTrue(laterIndex >= 0, () -> "Missing expected SQL: " + later);
        assertTrue(earlierIndex < laterIndex, () -> earlier + " must appear before " + later);
    }
}
