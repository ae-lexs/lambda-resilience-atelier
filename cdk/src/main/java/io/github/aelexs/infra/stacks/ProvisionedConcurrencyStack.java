package io.github.aelexs.infra.stacks;

import software.amazon.awscdk.CfnOutput;
import software.amazon.awscdk.Duration;
import software.amazon.awscdk.RemovalPolicy;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.Tags;
import software.amazon.awscdk.aws_apigatewayv2_integrations.HttpLambdaIntegration;
import software.amazon.awscdk.aws_apigatewayv2_integrations.HttpLambdaIntegrationProps;
import software.amazon.awscdk.services.applicationautoscaling.CronOptions;
import software.amazon.awscdk.services.applicationautoscaling.Schedule;
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
import software.amazon.awscdk.services.lambda.AutoScalingOptions;
import software.amazon.awscdk.services.lambda.Code;
import software.amazon.awscdk.services.lambda.Function;
import software.amazon.awscdk.services.lambda.IScalableFunctionAttribute;
import software.amazon.awscdk.services.lambda.ILayerVersion;
import software.amazon.awscdk.services.lambda.LayerVersion;
import software.amazon.awscdk.services.lambda.LoggingFormat;
import software.amazon.awscdk.services.lambda.Runtime;
import software.amazon.awscdk.services.lambda.Tracing;
import software.amazon.awscdk.services.lambda.UtilizationScalingOptions;
import software.amazon.awscdk.services.lambda.Version;
import software.amazon.awscdk.services.applicationautoscaling.ScalingSchedule;
import software.amazon.awscdk.services.logs.LogGroup;
import software.amazon.awscdk.services.logs.RetentionDays;
import software.constructs.Construct;

import java.util.List;
import java.util.Map;

public class ProvisionedConcurrencyStack extends Stack {

    public ProvisionedConcurrencyStack(final Construct scope, final String id, final StackProps props) {
        super(scope, id, props);

        // ── Resource tags ────────────────────────────────────────────────────
        // Grafana Cloud's CloudWatch data source silently excludes resources
        // that have no tags from its metric scrape jobs. Apply at the stack
        // level so all resources in this stack inherit both tags automatically.
        Tags.of(this).add("project", "lambda-resilience-atelier");
        Tags.of(this).add("module", "provisioned-concurrency");

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
        // PC-specific behaviour: invocations served from the PC pool emit
        // standard REPORT lines with NO Init Duration and NO Restore Duration.
        // They look identical to warm invocations. Spillover invocations
        // (when traffic exceeds PC capacity) DO carry Init Duration in the
        // REPORT line — same shape as Module 02 cold starts. The distinction
        // between "PC-served" and "warm-reuse" is only visible in CloudWatch
        // Metrics (ProvisionedConcurrencyInvocations), not in logs.
        LogGroup logGroup = LogGroup.Builder.create(this, "LambdaLogGroup")
            .logGroupName("/aws/lambda/lra-provisioned-concurrency")
            .retention(RetentionDays.ONE_WEEK)
            .removalPolicy(RemovalPolicy.DESTROY)
            .build();

        // ── ADOT Java Auto-Instrumentation Agent layer ───────────────────────
        // Instruments Spring Web MVC, AWS SDK v2, and the Lambda invocation
        // context automatically — no application code changes required.
        // Verify the latest ARN at:
        // https://aws-otel.github.io/docs/getting-started/lambda/lambda-java-auto-instr
        //
        // PC-specific note: the ADOT agent's class-loading and
        // instrumentation work happens during init for each pre-warmed
        // environment. With PC = 2, the init runs twice at allocation
        // time (Module 02-class init cost × 2), but every subsequent
        // invocation hits a fully-warmed environment with zero init delay.
        ILayerVersion adotLayer = LayerVersion.fromLayerVersionArn(
            this,
            "AdotJavaAgentLayer",
            "arn:aws:lambda:us-east-1:901920570463:layer:aws-otel-java-agent-amd64-ver-1-32-0:6"
        );

        // ── Lambda Function — NO SnapStart ──────────────────────────────────
        // SnapStart and Provisioned Concurrency are mutually exclusive on the
        // same function version: enabling both produces a CloudFormation
        // ResourceConflictException at deploy time. PC pre-allocates running
        // environments rather than restoring from a snapshot — the two
        // approaches are alternatives, not stackable layers.
        Function lambdaFn = Function.Builder.create(this, "SpringBootLambda")
            .functionName("lra-provisioned-concurrency")
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
            .environment(Map.of(
                // /opt/otel-handler instruments the JVM via -javaagent at the
                // process level — works for both RequestHandler and
                // RequestStreamHandler. The layer does not ship otel-stream-handler.
                "AWS_LAMBDA_EXEC_WRAPPER", "/opt/otel-handler",
                "OTEL_SERVICE_NAME", "lra-provisioned-concurrency"
            ))
            .build();

        // ── Published Version ────────────────────────────────────────────────
        // currentVersion publishes a new version each time the function
        // configuration or asset hash changes. PC is configured per-version
        // via the alias below — when the alias shifts to a new version,
        // Application Auto Scaling re-allocates PC against the new version,
        // re-running init. Plan deploys outside peak traffic windows for
        // this reason.
        Version version = lambdaFn.getCurrentVersion();

        // ── Alias with Provisioned Concurrency ───────────────────────────────
        // provisionedConcurrentExecutions(2) tells Lambda to keep 2 fully-
        // initialized execution environments running 24/7 against this
        // alias. PC = 2 is the minimum that exercises both the "PC-served"
        // and the "spillover" paths under load tests later in this module.
        // Production values are typically 5–500 depending on peak burst.
        //
        // Cost: 2 × 1.0 GB × 86,400 s × $0.0000041667 = $0.72/day in
        // us-east-1 — see "Cost Warning" at the top of this module.
        Alias alias = Alias.Builder.create(this, "LiveAlias")
            .aliasName("live")
            .version(version)
            .provisionedConcurrentExecutions(2)
            .build();

        // ── Application Auto Scaling — target tracking ───────────────────────
        // addAutoScaling registers the alias as a scalable target with
        // Application Auto Scaling under the lambda:function:ProvisionedConcurrency
        // dimension. Returns IScalableFunctionAttribute, on which we layer
        // utilization-based and schedule-based scaling policies.
        //
        // minCapacity = 2 keeps the steady-state PC at the alias's initial
        // value. Setting minCapacity below the alias's PC would let
        // autoscaling scale the function down past the deployed level, which
        // is rarely what you want — the deployed PC value is the floor.
        IScalableFunctionAttribute scalable = alias.addAutoScaling(
            AutoScalingOptions.builder()
                .minCapacity(2)
                .maxCapacity(10)
                .build());

        // Target tracking on ProvisionedConcurrencyUtilization. Creates two
        // CloudWatch alarms (scale-up at >70%, scale-down at <63% which is
        // 90% of the 70% target) and a target-tracking scaling policy that
        // adjusts PC to keep utilization near 70%.
        //
        // 70% is the AWS-recommended starting point for steady traffic.
        // Tighter (e.g., 50%) over-provisions and pays more idle. Looser
        // (e.g., 90%) under-provisions and increases spillover risk.
        scalable.scaleOnUtilization(UtilizationScalingOptions.builder()
            .utilizationTarget(0.7)
            .build());

        // ── Application Auto Scaling — scheduled (example) ───────────────────
        // Demonstrates the scheduled-scaling pattern. Cron expression below
        // bumps min capacity from 2 to 5 at 08:00 UTC weekdays. The
        // expectation is that you bump up *ahead* of a known traffic peak
        // (e.g., 30–60 minutes before market open) so PC is fully allocated
        // when the wave arrives.
        //
        // Production deployments typically have a "scale up at 07:30" and
        // a "scale back down at 18:00" pair. This codelab includes only the
        // morning ramp to keep the example readable.
        scalable.scaleOnSchedule("WeekdayMorningRamp",
            ScalingSchedule.builder()
                .schedule(Schedule.cron(CronOptions.builder()
                    .hour("8")
                    .minute("0")
                    .weekDay("MON-FRI")
                    .build()))
                .minCapacity(5)
                .maxCapacity(10)
                .build());

        // ── API Gateway HTTP API — integrate with the alias, not $LATEST ─────
        // PC requires traffic to flow through a published version (via an
        // alias) — pointing at $LATEST would bypass the PC pool entirely
        // and pay full cold init on every invocation.
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
            .apiName("lra-provisioned-concurrency-api")
            .defaultIntegration(integration)
            .build();

        CfnOutput.Builder.create(this, "ApiUrl")
            .value(httpApi.getApiEndpoint())
            .description("API Gateway HTTP API endpoint")
            .build();

        CfnOutput.Builder.create(this, "FunctionVersion")
            .value(version.getVersion())
            .description("Published function version (PC allocated against this version)")
            .build();
    }
}
