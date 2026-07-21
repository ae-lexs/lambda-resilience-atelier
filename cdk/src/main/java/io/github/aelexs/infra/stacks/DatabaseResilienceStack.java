package io.github.aelexs.infra.stacks;

import software.amazon.awscdk.CfnOutput;
import software.amazon.awscdk.Duration;
import software.amazon.awscdk.RemovalPolicy;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.Tags;
import software.amazon.awscdk.aws_apigatewayv2_integrations.HttpLambdaIntegration;
import software.amazon.awscdk.aws_apigatewayv2_integrations.HttpLambdaIntegrationProps;
import software.amazon.awscdk.services.apigatewayv2.HttpApi;
import software.amazon.awscdk.services.apigatewayv2.PayloadFormatVersion;
import software.amazon.awscdk.services.ec2.InterfaceVpcEndpointAwsService;
import software.amazon.awscdk.services.ec2.InterfaceVpcEndpointOptions;
import software.amazon.awscdk.services.ec2.IpAddresses;
import software.amazon.awscdk.services.ec2.SecurityGroup;
import software.amazon.awscdk.services.ec2.SubnetConfiguration;
import software.amazon.awscdk.services.ec2.SubnetSelection;
import software.amazon.awscdk.services.ec2.SubnetType;
import software.amazon.awscdk.services.ec2.Vpc;
import software.amazon.awscdk.services.lambda.Alias;
import software.amazon.awscdk.services.lambda.Code;
import software.amazon.awscdk.services.lambda.Function;
import software.amazon.awscdk.services.lambda.ILayerVersion;
import software.amazon.awscdk.services.lambda.LayerVersion;
import software.amazon.awscdk.services.lambda.LoggingFormat;
import software.amazon.awscdk.services.lambda.Runtime;
import software.amazon.awscdk.services.lambda.SnapStartConf;
import software.amazon.awscdk.services.lambda.Tracing;
import software.amazon.awscdk.services.lambda.Version;
import software.amazon.awscdk.services.logs.LogGroup;
import software.amazon.awscdk.services.logs.RetentionDays;
import software.amazon.awscdk.services.rds.AuroraPostgresClusterEngineProps;
import software.amazon.awscdk.services.rds.AuroraPostgresEngineVersion;
import software.amazon.awscdk.services.rds.ClusterInstance;
import software.amazon.awscdk.services.rds.Credentials;
import software.amazon.awscdk.services.rds.DatabaseCluster;
import software.amazon.awscdk.services.rds.DatabaseClusterEngine;
import software.amazon.awscdk.services.rds.DatabaseProxy;
import software.amazon.awscdk.services.rds.PerformanceInsightRetention;
import software.amazon.awscdk.services.rds.ProxyTarget;
import software.amazon.awscdk.services.rds.ServerlessV2ClusterInstanceProps;
import software.constructs.Construct;

import java.util.List;
import java.util.Map;

public class DatabaseResilienceStack extends Stack {

    public DatabaseResilienceStack(final Construct scope, final String id, final StackProps props) {
        super(scope, id, props);

        // ── Resource tags ────────────────────────────────────────────────────
        // Grafana Cloud's CloudWatch data source silently excludes resources
        // that have no tags from its metric scrape jobs. Apply at the stack
        // level so all resources in this stack inherit both tags automatically.
        Tags.of(this).add("project", "lambda-resilience-atelier");
        Tags.of(this).add("module", "database-resilience");

        // ── VPC — private isolated subnets, no NAT Gateway ──────────────────
        Vpc vpc = Vpc.Builder.create(this, "Vpc")
            .ipAddresses(IpAddresses.cidr("10.0.0.0/16"))
            .maxAzs(2)
            .natGateways(0)
            .subnetConfiguration(List.of(
                SubnetConfiguration.builder()
                    .subnetType(SubnetType.PRIVATE_ISOLATED)
                    .name("isolated")
                    .cidrMask(24)
                    .build()
            ))
            .build();

        // ── VPC Interface Endpoints ──────────────────────────────────────────
        // CloudWatch Logs — carried over from Module 01.
        vpc.addInterfaceEndpoint("CloudWatchLogsEndpoint",
            InterfaceVpcEndpointOptions.builder()
                .service(InterfaceVpcEndpointAwsService.CLOUDWATCH_LOGS)
                .build());

        // X-Ray — carried over from Module 02.
        vpc.addInterfaceEndpoint("XRayEndpoint",
            InterfaceVpcEndpointOptions.builder()
                .service(InterfaceVpcEndpointAwsService.XRAY)
                .build());

        // Secrets Manager — NEW for Module 05. The Lambda doesn't read the DB
        // secret directly (RDS Proxy does) but the AWS SDK's RdsUtilities
        // reaches Secrets Manager during init under some IAM-credential
        // resolution paths. Without this endpoint, Lambda init may hang or
        // time out in private isolated subnets. Cost: ~$0.02/hr × 2 AZs ≈
        // $0.96/day.
        vpc.addInterfaceEndpoint("SecretsManagerEndpoint",
            InterfaceVpcEndpointOptions.builder()
                .service(InterfaceVpcEndpointAwsService.SECRETS_MANAGER)
                .build());

        // ── CloudWatch Log Group ─────────────────────────────────────────────
        LogGroup logGroup = LogGroup.Builder.create(this, "LambdaLogGroup")
            .logGroupName("/aws/lambda/lra-database-resilience")
            .retention(RetentionDays.ONE_WEEK)
            .removalPolicy(RemovalPolicy.DESTROY)
            .build();

        // ── ADOT Java Auto-Instrumentation Agent layer ───────────────────────
        ILayerVersion adotLayer = LayerVersion.fromLayerVersionArn(
            this,
            "AdotJavaAgentLayer",
            "arn:aws:lambda:us-east-1:901920570463:layer:aws-otel-java-agent-amd64-ver-1-32-0:6"
        );

        // ── Aurora Serverless v2 — PostgreSQL 16 ─────────────────────────────
        // Auto-pause after 5 minutes idle. Min 0.5 ACU, max 1 ACU — keeps the
        // codelab's bill predictable. Production workloads typically size
        // max ACU based on peak query load: 0.5 ACU sustains ~25 concurrent
        // small queries; 4 ACU sustains ~200; 16 ACU starts approximating
        // a small RDS instance.
        //
        // Credentials are auto-generated and stored in a Secrets Manager
        // secret managed by CDK. The secret rotates automatically if you
        // configure a rotation schedule (out of scope for this module).
        DatabaseCluster auroraCluster = DatabaseCluster.Builder.create(this, "Aurora")
            .clusterIdentifier("lra-aurora-cluster")
            .engine(DatabaseClusterEngine.auroraPostgres(
                AuroraPostgresClusterEngineProps.builder()
                    // 16.4 was retired by RDS and no longer accepts cluster
                    // creation ("Cannot find version 16.4 for
                    // aurora-postgresql"). 16.13 is the current 16.x release.
                    // Check `aws rds describe-db-engine-versions --engine
                    // aurora-postgresql` before a from-scratch deploy; AWS
                    // retires minor versions on a rolling basis.
                    .version(AuroraPostgresEngineVersion.VER_16_13)
                    .build()))
            .vpc(vpc)
            .vpcSubnets(SubnetSelection.builder()
                .subnetType(SubnetType.PRIVATE_ISOLATED)
                .build())
            .writer(ClusterInstance.serverlessV2("writer",
                ServerlessV2ClusterInstanceProps.builder()
                    // Performance Insights is what makes DB-tier saturation
                    // visible. It publishes DBLoad, DBLoadCPU, DBLoadNonCPU
                    // and DBLoadRelativeToNumVCPUs into the AWS/RDS
                    // CloudWatch namespace (dimension: DBInstanceIdentifier),
                    // which is how Grafana reads them. Note that the PI
                    // *console* reaches end of support on 2026-07-31; the
                    // CloudWatch metrics and the PI API are unaffected, so
                    // dashboards built on them outlive that date.
                    //
                    // Caveat for panel design: these metrics are published
                    // only while there is load on the instance, so idle
                    // windows appear as gaps rather than zeros.
                    .enablePerformanceInsights(true)
                    // Default = 7 days, which is the free tier. Only
                    // long-term retention (1-24 months) is billed.
                    .performanceInsightRetention(PerformanceInsightRetention.DEFAULT)
                    .build()))
            // Fixed capacity — min == max == 2 ACUs. Two reasons to pin the
            // range rather than let it float:
            //
            //   1. Autoscaling masks the effect under measurement. A cluster
            //      that grows under load hides the very ceiling these
            //      experiments exist to locate.
            //   2. Performance Insights carries memory overhead, and AWS
            //      recommends a minimum of 2 ACUs when it is enabled. Below
            //      that, a low maximum can drive the instance into
            //      `incompatible-parameters` via out-of-memory restarts.
            //
            // Auto-pause is deliberately absent. `SecondsUntilAutoPause` is
            // only valid at a minimum capacity of 0 — and it never took
            // effect here regardless: an attached RDS Proxy holds an open
            // connection to every instance in the cluster, which prevents
            // Aurora serverless instances from auto-pausing at all.
            .serverlessV2MinCapacity(2)
            .serverlessV2MaxCapacity(2)
            .credentials(Credentials.fromGeneratedSecret("postgres"))
            .defaultDatabaseName("lra")
            .iamAuthentication(true)
            .storageEncrypted(true)
            .removalPolicy(RemovalPolicy.DESTROY)
            .deletionProtection(false)
            .build();

        // ── RDS Proxy ────────────────────────────────────────────────────────
        // The proxy uses the cluster's auto-generated Secrets Manager secret
        // to authenticate to Aurora. Clients (the Lambda) authenticate to the
        // proxy with IAM tokens — see Step 1's two-hop auth walkthrough.
        //
        // requireTls(true): forces TLS on Lambda → Proxy. Without this, IAM
        // tokens travel in plaintext on the VPC network — non-fatal in a
        // single-AZ private subnet, but a no-brainer to enable.
        DatabaseProxy proxy = DatabaseProxy.Builder.create(this, "Proxy")
            .dbProxyName("lra-rds-proxy")
            .proxyTarget(ProxyTarget.fromCluster(auroraCluster))
            .secrets(List.of(auroraCluster.getSecret()))
            .vpc(vpc)
            .vpcSubnets(SubnetSelection.builder()
                .subnetType(SubnetType.PRIVATE_ISOLATED)
                .build())
            .iamAuth(true)
            .requireTls(true)
            .idleClientTimeout(Duration.minutes(30))
            .maxConnectionsPercent(80)
            .maxIdleConnectionsPercent(50)
            .build();

        // ── Lambda Function — SnapStart enabled, NO PC ───────────────────────
        Function lambdaFn = Function.Builder.create(this, "SpringBootLambda")
            .functionName("lra-database-resilience")
            .runtime(Runtime.JAVA_21)
            .code(Code.fromAsset("../api/build/libs/api.jar"))
            .handler("io.github.aelexs.api.StreamLambdaHandler::handleRequest")
            .memorySize(1024)
            .timeout(Duration.seconds(29))
            .vpc(vpc)
            .vpcSubnets(SubnetSelection.builder()
                .subnetType(SubnetType.PRIVATE_ISOLATED)
                .build())
            .logGroup(logGroup)
            .loggingFormat(LoggingFormat.TEXT)
            .layers(List.of(adotLayer))
            .tracing(Tracing.ACTIVE)
            .snapStart(SnapStartConf.ON_PUBLISHED_VERSIONS)
            .environment(Map.of(
                "AWS_LAMBDA_EXEC_WRAPPER", "/opt/otel-handler",
                "OTEL_SERVICE_NAME", "lra-database-resilience",
                // NEW: DB connection environment for HikariCP. The presence
                // of DB_PROXY_ENDPOINT is the activation signal for
                // IamTokenAuthDataSourceConfig and DbController on the
                // application side (both gated by @ConditionalOnProperty).
                // Earlier modules' stacks that omit this var keep the
                // shared api/ JAR deployable without DB wiring.
                "DB_PROXY_ENDPOINT", proxy.getEndpoint(),
                "DB_NAME", "lra",
                "DB_USER", "postgres",
                // Opts this stack into Spring Boot's SQL init (schema.sql +
                // data.sql). application.properties reads the value via
                // ${SPRING_SQL_INIT_MODE:never}; without this entry the
                // default "never" applies and SQL init is skipped — the
                // mode that earlier modules' stacks rely on.
                "SPRING_SQL_INIT_MODE", "always"
            ))
            .build();

        // ── IAM grants for the two-hop auth model ────────────────────────────
        // grantConnect adds rds-db:connect permission on the proxy resource
        // ARN, scoped to the specified DB user. The Lambda execution role
        // can now mint IAM auth tokens for connecting as 'postgres'.
        proxy.grantConnect(lambdaFn, "postgres");

        // Lambda → Proxy (port 5432) on the security-group level. CDK's
        // grantConnect also opens this on the connections side, but being
        // explicit prevents surprises when SG rules interact with custom
        // changes later.
        lambdaFn.getConnections().allowTo(proxy, software.amazon.awscdk.services.ec2.Port.tcp(5432));

        // ── Published Version + Alias (Module 03 SnapStart pattern) ─────────
        Version version = lambdaFn.getCurrentVersion();

        Alias alias = Alias.Builder.create(this, "LiveAlias")
            .aliasName("live")
            .version(version)
            .description("Module 05: SnapStart + Aurora Serverless v2 + RDS Proxy")
            .build();

        // ── API Gateway HTTP API — alias-targeted ────────────────────────────
        HttpLambdaIntegration integration = new HttpLambdaIntegration(
            "LambdaIntegration",
            alias,
            HttpLambdaIntegrationProps.builder()
                .payloadFormatVersion(PayloadFormatVersion.VERSION_1_0)
                .build());

        HttpApi httpApi = HttpApi.Builder.create(this, "HttpApi")
            .apiName("lra-database-resilience-api")
            .defaultIntegration(integration)
            .build();

        CfnOutput.Builder.create(this, "ApiUrl")
            .value(httpApi.getApiEndpoint())
            .description("API Gateway HTTP API endpoint")
            .build();

        CfnOutput.Builder.create(this, "ProxyEndpoint")
            .value(proxy.getEndpoint())
            .description("RDS Proxy endpoint (DB_PROXY_ENDPOINT env var on Lambda)")
            .build();

        CfnOutput.Builder.create(this, "AuroraEndpoint")
            .value(auroraCluster.getClusterEndpoint().getHostname())
            .description("Aurora cluster writer endpoint — for Experiment 1 (no proxy)")
            .build();
    }
}
