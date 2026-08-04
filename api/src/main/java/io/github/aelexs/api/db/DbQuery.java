package io.github.aelexs.api.db;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;

/**
 * The single unit of work both arms of the breaker experiment perform.
 *
 * Existing as a separate class is the point. The experiment compares two
 * endpoints — /db (control) and /db-breaker (treatment) — and its central
 * invariant is that the work behind them is identical, so that any
 * difference in goodput or GB-seconds is attributable to the breaker and
 * nothing else. Two controllers each holding their own copy of the SQL
 * would make that invariant a convention someone has to remember; one
 * shared component makes it structural.
 *
 * The borrow is the interesting part, not the query. `SELECT now()` costs
 * the database almost nothing — what costs is acquiring a pooled
 * connection when Aurora is CPU-saturated, which PHASE_0 §VIII measured
 * degrading from 105 µs to 4.73 s. The call below therefore spends its
 * time inside JdbcTemplate's getConnection(), not inside Postgres.
 */
@Component
@ConditionalOnProperty(name = "DB_PROXY_ENDPOINT")
public class DbQuery {

    private final JdbcTemplate jdbcTemplate;

    public DbQuery(final JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Map<String, Object> now() {
        Instant dbNow = jdbcTemplate.queryForObject(
            "SELECT now()::timestamp", Instant.class);
        return Map.of(
            "db_now", dbNow != null ? dbNow.toString() : "(null)",
            "lambda_now", Instant.now().toString());
    }
}
