package io.github.aelexs.api.db;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.rds.RdsUtilities;
import software.amazon.awssdk.services.rds.model.GenerateAuthenticationTokenRequest;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * Wraps HikariDataSource with an IAM-token password provider that
 * regenerates the 15-minute token before every getConnection() call.
 * The token is read by HikariCP's PoolEntryCreator at pool-growth time,
 * so any newly-created pool entry uses the latest token — eliminating
 * the snapshot-freeze design flaw where a token captured at SnapStart
 * version-publish time would expire before the snapshot is restored
 * 15+ minutes later.
 *
 * Why subclass and override getConnection() rather than rely on
 * setPassword() at init time:
 *
 *   - At init time only: the password lives in the snapshot. After
 *     15 minutes (IAM token lifetime), every restored container has
 *     an expired token and connection auth fails with
 *     "FATAL: The IAM authentication failed for the role X". This
 *     was the original design and it broke under SnapStart's
 *     long-lived snapshots.
 *
 *   - Per-borrow refresh (this class): setPassword() updates the
 *     HikariConfig field that PoolEntryCreator reads when growing the
 *     pool. Refreshing on every borrow is cheap (token generation is
 *     a local HMAC, ~1ms) and ensures any new connection — including
 *     ones created on pool growth or maxLifetime-driven recycling —
 *     uses a current token. Existing pool entries (already
 *     authenticated TCP connections) remain valid until they age out
 *     via maxLifetime.
 *
 * Spring Boot 3.2+'s HikariCheckpointRestoreLifecycle still drains
 * the pool on beforeCheckpoint and re-initializes on afterRestore;
 * the snapshot captures an empty pool. The override here ensures the
 * first borrow after restore mints a fresh token before any
 * connection is created. Module 03's InvokePrimingResource pattern is
 * for JIT priming, which still applies; the database lifecycle is
 * separately framework-managed plus this token-refresh hook.
 */
@Configuration
public class IamTokenAuthDataSourceConfig {

    private final String dbProxyEndpoint;
    private final String dbName;
    private final String dbUser;
    private final String region;

    public IamTokenAuthDataSourceConfig(
            @Value("${DB_PROXY_ENDPOINT}") String dbProxyEndpoint,
            @Value("${DB_NAME}") String dbName,
            @Value("${DB_USER}") String dbUser,
            @Value("${AWS_REGION:us-east-1}") String region) {
        this.dbProxyEndpoint = dbProxyEndpoint;
        this.dbName = dbName;
        this.dbUser = dbUser;
        this.region = region;
    }

    @Bean
    @Primary
    @ConfigurationProperties("spring.datasource.hikari")
    public DataSource dataSource() {
        // RdsUtilities is created once per datasource instance — the SDK
        // client is thread-safe and reusable. Token generation itself is
        // a local cryptographic operation (HMAC over the host + user +
        // expiry), so generating a fresh token per connection borrow has
        // negligible cost.
        final RdsUtilities rdsUtilities = RdsUtilities.builder()
            .region(Region.of(region))
            .credentialsProvider(DefaultCredentialsProvider.create())
            .build();

        // Anonymous subclass overrides getConnection() to refresh the IAM
        // token before each pool action. setPassword() updates the
        // HikariConfig.password field that PoolEntryCreator reads when it
        // grows the pool — so any new connections created on borrow paths
        // (initial growth, maxLifetime recycling, pool resize) use a fresh
        // token. The cost is one HMAC computation per borrow, ~1ms.
        HikariDataSource ds = new HikariDataSource() {
            @Override
            public Connection getConnection() throws SQLException {
                setPassword(generateToken(rdsUtilities));
                return super.getConnection();
            }

            @Override
            public Connection getConnection(String username, String password)
                    throws SQLException {
                setPassword(generateToken(rdsUtilities));
                return super.getConnection(username, password);
            }
        };
        ds.setJdbcUrl("jdbc:postgresql://" + dbProxyEndpoint + ":5432/" + dbName
            + "?ssl=true&sslmode=require");
        ds.setUsername(dbUser);
        ds.setDriverClassName("org.postgresql.Driver");

        // Initial token for the first pool start. HikariCP's pool may
        // pre-warm minimum-idle connections at start-up before any
        // application code calls getConnection(); set a valid token so
        // that initial warm-up works. Subsequent borrows refresh it.
        ds.setPassword(generateToken(rdsUtilities));

        return ds;
    }

    private String generateToken(RdsUtilities rdsUtilities) {
        GenerateAuthenticationTokenRequest request =
            GenerateAuthenticationTokenRequest.builder()
                .hostname(dbProxyEndpoint)
                .port(5432)
                .username(dbUser)
                .build();
        return rdsUtilities.generateAuthenticationToken(request);
    }

    // SQL init: handled entirely by Spring Boot's
    // SqlInitializationAutoConfiguration, which discovers the custom
    // @Primary DataSource above and wires its own
    // DataSourceScriptDatabaseInitializer against it. Declaring a manual
    // bean here with the same name produced a BeanDefinitionOverrideException
    // — the @ConditionalOnMissingBean on the auto-config matches by type,
    // not by user-bean-presence. Trust the auto-config; configure via
    // spring.sql.init.* properties only.
}
