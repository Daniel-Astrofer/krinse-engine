package com.kerosene.kfe.ledger.adapters.out.persistence;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** Read-only integrity checkpoint of the authoritative PostgreSQL ledger.
 * Rows are streamed in key order under one consistent MVCC snapshot. No wallet
 * identifiers, balances, or journal payloads leave this adapter.
 */
@Component
@ConditionalOnProperty(name = "kfe.ledger.node-sync.enabled", havingValue = "true")
public class KfeLedgerCheckpointReader {
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public KfeLedgerCheckpointReader(JdbcTemplate jdbc, @Qualifier("ledgerClock") Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ, timeout = 30)
    public Root read() {
        long observedAt = clock.millis();
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            field(digest, "KEROSENE_KFE_POSTGRES_LEDGER_V1");
            field(digest, "financial.balances_core");
            long balances = rows(digest,
                    "select row_to_json(t)::text from financial.balances_core t order by wallet_id, asset");
            field(digest, "financial.balance_movements");
            long postings = rows(digest,
                    "select row_to_json(t)::text from financial.balance_movements t order by id");
            return new Root(HexFormat.of().formatHex(digest.digest()), balances, postings, observedAt);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private long rows(MessageDigest digest, String sql) {
        return jdbc.query(connection -> {
            var statement = connection.prepareStatement(sql);
            statement.setFetchSize(512);
            statement.setQueryTimeout(15);
            return statement;
        }, (ResultSetExtractor<Long>) result -> {
            long count = 0;
            while (result.next()) {
                field(digest, result.getString(1));
                count = Math.incrementExact(count);
            }
            return count;
        });
    }

    static void field(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Long.BYTES).putLong(bytes.length).array());
        digest.update(bytes);
    }

    public record Root(String ledgerRoot, long balancesCount, long postingsCount, long observedAtMs) { }
}
