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
import software.constructs.Construct;

import java.util.List;
import java.util.Map;

public class SnapStartStack extends Stack {

    public SnapStartStack(final Construct scope, final String id, final StackProps props) {
        super(scope, id, props);

        // ── Resource tags ────────────────────────────────────────────────────
        // Grafana Cloud's CloudWatch data source silently excludes resources
        // that have no tags from its metric scrape jobs. Apply at the stack
        // level so all resources in this stack inherit both tags automatically.
        Tags.of(this).add("project", "lambda-resilience-atelier");
        Tags.of(this).add("module", "snapstart");

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
        // CloudWatch Logs — carried over from Module 01 (Lambda log delivery).
        vpc.addInterfaceEndpoint("CloudWatchLogsEndpoint",
            InterfaceVpcEndpointOptions.builder()
                .service(InterfaceVpcEndpointAwsService.CLOUDWATCH_LOGS)
                .build());

        // X-Ray — carried over from Module 02. The ADOT collector exports
        // trace segments here instead of to a public internet endpoint.
        // Without this endpoint the ADOT collector silently drops all trace
        // segments — no error in the Lambda logs, no traces in X-Ray.
        vpc.addInterfaceEndpoint("XRayEndpoint",
            InterfaceVpcEndpointOptions.builder()
                .service(InterfaceVpcEndpointAwsService.XRAY)
                .build());

        // ── CloudWatch Log Group ─────────────────────────────────────────────
        // SnapStart-specific behaviour: the one-time INIT_REPORT line for the
        // snapshot capture lands in this log group at version-publish time,
        // separately from per-invocation REPORT lines. Per-invocation REPORTs
        // for SnapStart functions carry `Restore Duration` instead of
        // `Init Duration` — see CloudWatch Logs Insights query in Step 7.
        LogGroup logGroup = LogGroup.Builder.create(this, "LambdaLogGroup")
            .logGroupName("/aws/lambda/lra-snapstart")
            .retention(RetentionDays.ONE_WEEK)
            .removalPolicy(RemovalPolicy.DESTROY)
            .build();

        // ── ADOT Java Auto-Instrumentation Agent layer ───────────────────────
        // Instruments Spring Web MVC, AWS SDK v2, and the Lambda invocation
        // context automatically — no application code changes required.
        // Verify the latest ARN at:
        // https://aws-otel.github.io/docs/getting-started/lambda/lambda-java-auto-instr
        //
        // SnapStart-specific note: the ADOT agent's class-loading and
        // instrumentation work happens during the snapshotted init phase, so
        // its cold-start cost (Module 02 added ~4 s of Init Duration) is paid
        // once at publish time and amortized across every restored environment.
        ILayerVersion adotLayer = LayerVersion.fromLayerVersionArn(
            this,
            "AdotJavaAgentLayer",
            "arn:aws:lambda:us-east-1:901920570463:layer:aws-otel-java-agent-amd64-ver-1-32-0:6"
        );

        // ── Lambda Function — SnapStart enabled ──────────────────────────────
        Function lambdaFn = Function.Builder.create(this, "SpringBootLambda")
            .functionName("lra-snapstart")
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
            // Tracing.ACTIVE enables X-Ray active tracing and automatically
            // grants xray:PutTraceSegments + xray:PutTelemetryRecords to the
            // Lambda execution role. The ADOT collector writes via the X-Ray
            // VPC endpoint — no internet access required.
            .tracing(Tracing.ACTIVE)
            // SnapStart — the single property that enables snapshotting.
            // ON_PUBLISHED_VERSIONS is the only meaningful value; the
            // alternative (NONE) is the default. SnapStart and Provisioned
            // Concurrency are mutually exclusive on the same alias — see
            // Step 9 (decision criterion) and Module 04.
            .snapStart(SnapStartConf.ON_PUBLISHED_VERSIONS)
            .environment(Map.of(
                // /opt/otel-handler instruments the JVM via -javaagent at the
                // process level — works for both RequestHandler and
                // RequestStreamHandler. The layer does not ship otel-stream-handler.
                "AWS_LAMBDA_EXEC_WRAPPER", "/opt/otel-handler",
                "OTEL_SERVICE_NAME", "lra-snapstart"
            ))
            .build();

        // ── Published Version ────────────────────────────────────────────────
        // currentVersion publishes a new version each time the function
        // configuration or asset hash changes. SnapStart takes a snapshot of
        // *this* version's initialized state — every published version has
        // its own snapshot. The hash does not include CloudFormation parameters
        // or IAM policy changes; if you need a fresh snapshot for those, force
        // a no-op tweak (e.g., bump the function description). See Pitfalls.
        Version version = lambdaFn.getCurrentVersion();

        // ── Alias ────────────────────────────────────────────────────────────
        // API Gateway integrates with the alias; the alias points at the
        // version. To deploy a new SnapStart-enabled version, publish it,
        // then shift the alias. Aliases support weighted routing for canary
        // releases (Alias.addVersionRoutingConfig) — out of scope here.
        Alias alias = Alias.Builder.create(this, "LiveAlias")
            .aliasName("live")
            .version(version)
            .build();

        // ── API Gateway HTTP API — integrate with the alias, not $LATEST ─────
        // SnapStart only takes snapshots of published versions, so traffic
        // must flow through a version (via an alias) — not $LATEST. Pointing
        // API Gateway at the function directly would bypass the snapshot and
        // run a full cold init on every invocation.
        //
        // PayloadFormatVersion.VERSION_1_0 matches what
        // SpringBootLambdaContainerHandler.getAwsProxyHandler reads
        // (`AwsProxyRequest`). The library's v2.0 factory has known init-time
        // ClassCastExceptions at lib v2.1.5 + Spring Boot 3.4.1.
        HttpLambdaIntegration integration = new HttpLambdaIntegration(
            "LambdaIntegration",
            alias,
            HttpLambdaIntegrationProps.builder()
                .payloadFormatVersion(PayloadFormatVersion.VERSION_1_0)
                .build());

        HttpApi httpApi = HttpApi.Builder.create(this, "HttpApi")
            .apiName("lra-snapstart-api")
            .defaultIntegration(integration)
            .build();

        CfnOutput.Builder.create(this, "ApiUrl")
            .value(httpApi.getApiEndpoint())
            .description("API Gateway HTTP API endpoint")
            .build();

        CfnOutput.Builder.create(this, "FunctionVersion")
            .value(version.getVersion())
            .description("Published function version (snapshot taken at this version)")
            .build();
    }
}
