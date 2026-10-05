package com.kerosene.architecture;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class FinancialIntegrityMigrationTest {

    private static final Path PROJECT_ROOT = Path.of("").toAbsolutePath();
    private static final Path V17_MIGRATION = PROJECT_ROOT.resolve("src/main/resources/db/migration/V17__financial_integrity_constraints.sql");

    @Test
    void legacyLedgerEntryConstraintsAreSkippedWhenTableIsMissing() throws IOException {
        String migration = Files.readString(V17_MIGRATION);

        assertTrue(migration.contains("IF to_regclass('financial.ledger_entries') IS NOT NULL THEN"));
        assertAppearsBefore(migration,
                "IF to_regclass('financial.ledger_entries') IS NOT NULL THEN",
                "ALTER TABLE financial.ledger_entries");
    }

    private static void assertAppearsBefore(String text, String earlier, String later) {
        int earlierIndex = text.indexOf(earlier);
        int laterIndex = text.indexOf(later);

        assertTrue(earlierIndex >= 0, () -> "Missing expected SQL: " + earlier);
        assertTrue(laterIndex >= 0, () -> "Missing expected SQL: " + later);
        assertTrue(earlierIndex < laterIndex, () -> earlier + " must appear before " + later);
    }
}
