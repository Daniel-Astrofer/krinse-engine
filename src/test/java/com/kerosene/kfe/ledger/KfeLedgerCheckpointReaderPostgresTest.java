package com.kerosene.kfe.ledger;

import com.kerosene.kfe.ledger.adapters.out.persistence.KfeLedgerCheckpointReader;
import com.kerosene.kfe.ledger.adapters.out.persistence.KfeLedgerCheckpointReader.Root;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import static org.assertj.core.api.Assertions.assertThat;

/** Run against a disposable, dedicated PostgreSQL database only. */
@EnabledIfEnvironmentVariable(named = "KFE_CHECKPOINT_TEST_JDBC_URL", matches = ".+")
class KfeLedgerCheckpointReaderPostgresTest {
    private static final Clock CLOCK = Clock.fixed(Instant.ofEpochMilli(1_000_000), ZoneOffset.UTC);

    private KfeLedgerCheckpointReader reader(PGSimpleDataSource ds, JdbcTemplate jdbc) {
        ProxyFactory proxy = new ProxyFactory(new KfeLedgerCheckpointReader(jdbc, CLOCK));
        var transactions = new TransactionInterceptor();
        transactions.setTransactionManager(new DataSourceTransactionManager(ds));
        transactions.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        proxy.addAdvice(transactions);
        return (KfeLedgerCheckpointReader) proxy.getProxy();
    }

    @Test void readsOneConsistentSnapshotWhileBalancesAndPostingsChangeConcurrently() throws Exception {
        var ds = new PGSimpleDataSource();
        ds.setURL(System.getenv("KFE_CHECKPOINT_TEST_JDBC_URL"));
        ds.setUser("postgres");
        ds.setPassword(System.getenv("KFE_CHECKPOINT_TEST_PASSWORD"));
        var setup = new JdbcTemplate(ds);
        setup.execute("create schema financial");
        setup.execute("create table financial.balances_core(wallet_id text, asset text, available_sats bigint)");
        setup.execute("create table financial.balance_movements(id bigint, amount_sats bigint)");
        setup.execute("insert into financial.balances_core values ('wallet', 'BTC', 10)");
        setup.execute("insert into financial.balance_movements values (1, 10)");
        Root before = reader(ds, setup).read();
        AtomicBoolean modified = new AtomicBoolean();
        JdbcTemplate interleaving = new JdbcTemplate(ds) {
            @Override public <T> T query(PreparedStatementCreator statement, ResultSetExtractor<T> extractor) {
                T result = super.query(statement, extractor);
                if (modified.compareAndSet(false, true)) {
                    try (Connection writer = ds.getConnection()) {
                        writer.setAutoCommit(false);
                        try (var command = writer.createStatement()) {
                            command.executeUpdate("update financial.balances_core set available_sats=20");
                            command.executeUpdate("insert into financial.balance_movements values (2, 10)");
                        }
                        writer.commit();
                    } catch (Exception exception) { throw new AssertionError(exception); }
                }
                return result;
            }
        };
        Root during = reader(ds, interleaving).read();
        assertThat(during).isEqualTo(before);
        Root after = reader(ds, setup).read();
        assertThat(after.ledgerRoot()).isNotEqualTo(before.ledgerRoot());
        assertThat(after.balancesCount()).isEqualTo(1);
        assertThat(after.postingsCount()).isEqualTo(2);
    }
}
