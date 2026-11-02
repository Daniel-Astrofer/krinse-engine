package com.kerosene.kfe.liquidity.adapters.out.persistence;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.sql.DriverManager;
import static org.assertj.core.api.Assertions.assertThat;

/** Runs the unchanged baseline and all upgrades against an empty, separate disposable database. */
@EnabledIfEnvironmentVariable(named="KFE_TREASURY_MIGRATION_POSTGRES_URL",matches=".+")
class TreasuryMigrationsPostgresTest {
    @Test void migratesExistingPendingAndUnknownJobsWithoutReplayingThem() throws Exception {
        String base=System.getenv("KFE_TREASURY_MIGRATION_POSTGRES_URL");
        String database="treasury_migration_"+java.util.UUID.randomUUID().toString().replace("-", "");
        try(var connection=DriverManager.getConnection(base,"kfe_treasury_test","");var statement=connection.createStatement()) {
            statement.executeUpdate("CREATE DATABASE " + database);
        }
        String url=base.substring(0,base.lastIndexOf('/')+1)+database;
        try {
        Flyway.configure().dataSource(url,"kfe_treasury_test","").locations("classpath:db/migration").target("63").load().migrate();
        try(var connection=DriverManager.getConnection(url,"kfe_treasury_test","");var statement=connection.createStatement()) {
            statement.executeUpdate("INSERT INTO financial.channel_rebalance_jobs(id,channel_point,status,estimated_cost_sats,expected_gain_sats) VALUES (gen_random_uuid(),'legacy:0','IN_PROGRESS',10,100),(gen_random_uuid(),'new:0','PENDING',10,100)");
        }
        var flyway=Flyway.configure().dataSource(url,"kfe_treasury_test","").locations("classpath:db/migration").load();
        flyway.migrate(); flyway.validate();
        try(var connection=DriverManager.getConnection(url,"kfe_treasury_test","");var statement=connection.createStatement();
            var results=statement.executeQuery("SELECT execution_phase FROM financial.channel_rebalance_jobs ORDER BY channel_point")) {
            assertThat(results.next()).isTrue();assertThat(results.getString(1)).isEqualTo("RECONCILING");
            assertThat(results.next()).isTrue();assertThat(results.getString(1)).isEqualTo("READY");
        }
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("65");
        try(var factory=new org.hibernate.cfg.Configuration()
                .addAnnotatedClass(com.kerosene.kfe.adapters.out.persistence.model.liquidity.KfeChannelRebalanceJobEntity.class)
                .addAnnotatedClass(com.kerosene.kfe.adapters.out.persistence.model.liquidity.KfeTreasuryProposalEntity.class)
                .setProperty("hibernate.connection.url",url)
                .setProperty("hibernate.connection.username","kfe_treasury_test")
                .setProperty("hibernate.connection.password","")
                .setProperty("hibernate.hbm2ddl.auto","validate").buildSessionFactory()) {
            assertThat(factory.isOpen()).isTrue();
        }

        } finally {
            try(var connection=DriverManager.getConnection(base,"kfe_treasury_test","");var statement=connection.createStatement()) {
                statement.executeUpdate("DROP DATABASE " + database);
            }
        }
    }
}
