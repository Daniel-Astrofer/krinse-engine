package com.kerosene.architecture;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class UniqueActiveWalletMigrationTest {

    private static final Path PROJECT_ROOT = Path.of("").toAbsolutePath();
    private static final Path V18_MIGRATION = PROJECT_ROOT.resolve("src/main/resources/db/migration/V18__unique_active_wallet_per_custody.sql");
    private static final Path V26_MIGRATION = PROJECT_ROOT.resolve("src/main/resources/db/migration/V26__wallet_custody_limits.sql");

    @Test
    void duplicateActiveWalletsAreArchivedBeforeUniqueIndex() throws IOException {
        String migration = Files.readString(V18_MIGRATION);

        assertTrue(migration.contains("ranked_single_custody_wallets"));
        assertTrue(migration.contains("ranked_cold_wallets"));
        assertTrue(migration.contains("SET status = 'ARCHIVED'"));
        assertTrue(migration.contains("WHERE kind IN ('INTERNAL', 'CUSTODIAL_ONCHAIN')"));
        assertTrue(migration.contains("ranked.active_rank > 2"));
        assertAppearsBefore(migration,
                "ranked_single_custody_wallets",
                "CREATE UNIQUE INDEX IF NOT EXISTS ux_wallets_core_user_kind_active_custody");
    }

    @Test
    void existingUniqueIndexIsRecreatedWithoutWatchOnly() throws IOException {
        String migration = Files.readString(V26_MIGRATION);

        assertTrue(migration.contains("DROP INDEX IF EXISTS financial.ux_wallets_core_user_kind_active_custody"));
        assertTrue(migration.contains("WHERE kind IN ('INTERNAL', 'CUSTODIAL_ONCHAIN')"));
        assertTrue(migration.contains("ranked.active_rank > 2"));
        assertAppearsBefore(migration,
                "DROP INDEX IF EXISTS financial.ux_wallets_core_user_kind_active_custody",
                "CREATE UNIQUE INDEX IF NOT EXISTS ux_wallets_core_user_kind_active_custody");
    }

    private static void assertAppearsBefore(String text, String earlier, String later) {
        int earlierIndex = text.indexOf(earlier);
        int laterIndex = text.indexOf(later);

        assertTrue(earlierIndex >= 0, () -> "Missing expected SQL: " + earlier);
        assertTrue(laterIndex >= 0, () -> "Missing expected SQL: " + later);
        assertTrue(earlierIndex < laterIndex, () -> earlier + " must appear before " + later);
    }
}
