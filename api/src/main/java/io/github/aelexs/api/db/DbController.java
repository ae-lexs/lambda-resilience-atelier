package io.github.aelexs.api.db;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;

/**
 * Minimal /db endpoint — issues SELECT now() against Aurora through the
 * RDS Proxy, returns the timestamp. Exists to (a) prove the connection
 * path works end-to-end and (b) provide a request shape for k6 to drive
 * during the connection-exhaustion experiment.
 *
 * @ConditionalOnProperty matches IamTokenAuthDataSourceConfig — without
 * the gate, Spring tries to autowire JdbcTemplate (which depends on the
 * conditional DataSource) on Modules 01–04 stacks where the DataSource
 * bean was correctly skipped, producing a "no qualifying DataSource"
 * failure on every stack that does not set DB_PROXY_ENDPOINT. Both
 * beans must share the same activation signal to keep the shared api/
 * JAR deployable across every module's stack.
 */
@RestController
@ConditionalOnProperty(name = "DB_PROXY_ENDPOINT")
public class DbController {

    private final JdbcTemplate jdbcTemplate;

    @Autowired
    public DbController(final JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @GetMapping("/db")
    public Map<String, Object> queryNow() {
        Instant dbNow = jdbcTemplate.queryForObject(
            "SELECT now()::timestamp", Instant.class);
        return Map.of(
            "db_now", dbNow != null ? dbNow.toString() : "(null)",
            "lambda_now", Instant.now().toString());
    }
}
