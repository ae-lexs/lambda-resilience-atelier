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
import software.amazon.awscdk.services.lambda.Code;
import software.amazon.awscdk.services.lambda.Function;
import software.amazon.awscdk.services.lambda.ILayerVersion;
import software.amazon.awscdk.services.lambda.LayerVersion;
import software.amazon.awscdk.services.lambda.LoggingFormat;
import software.amazon.awscdk.services.lambda.Runtime;
import software.amazon.awscdk.services.lambda.Tracing;
import software.amazon.awscdk.services.logs.LogGroup;
import software.amazon.awscdk.services.logs.RetentionDays;
import software.constructs.Construct;

import java.util.List;
import java.util.Map;

public class ObservabilityStack extends Stack {
    public ObservabilityStack(final Construct scope, final String id, final StackProps props) {
        super(scope, id, props);

        // ── Resource tags ────────────────────────────────────────────────────
        // Grafana Cloud's CloudWatch data source silently excludes resources
        // that have no tags from its metric scrape jobs. Apply at the stack
        // level so all resources in this stack inherit both tags automatically.
        Tags.of(this).add("project", "lambda-resilience-atelier");
        Tags.of(this).add("module", "observability");

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
        // CloudWatch Logs — carried over from Module 01 (Lambda log delivery)
        vpc.addInterfaceEndpoint("CloudWatchLogsEndpoint",
            InterfaceVpcEndpointOptions.builder()
                .service(InterfaceVpcEndpointAwsService.CLOUDWATCH_LOGS)
                .build());

        // X-Ray — new in Module 02. The ADOT collector exports trace segments
        // here instead of to a public internet endpoint. Without this endpoint
        // the ADOT collector silently drops all trace segments — no error in
        // the Lambda logs, no traces in X-Ray.
        vpc.addInterfaceEndpoint("XRayEndpoint",
            InterfaceVpcEndpointOptions.builder()
                .service(InterfaceVpcEndpointAwsService.XRAY)
                .build());

        // ── CloudWatch Log Group ─────────────────────────────────────────────
        LogGroup logGroup = LogGroup.Builder.create(this, "LambdaLogGroup")
            .logGroupName("/aws/lambda/lra-observability")
            .retention(RetentionDays.ONE_WEEK)
            .removalPolicy(RemovalPolicy.DESTROY)
            .build();

        // ── ADOT Java Auto-Instrumentation Agent layer ───────────────────────
        // Instruments Spring Web MVC, AWS SDK v2, and the Lambda invocation
        // context automatically — no application code changes required.
        // Verify the latest ARN at:
        // https://aws-otel.github.io/docs/getting-started/lambda/lambda-java-auto-instr
        ILayerVersion adotLayer = LayerVersion.fromLayerVersionArn(
            this,
            "AdotJavaAgentLayer",
            "arn:aws:lambda:us-east-1:901920570463:layer:aws-otel-java-agent-amd64-ver-1-32-0:6"
        );

        // ── Lambda Function ──────────────────────────────────────────────────
        Function lambdaFn = Function.Builder.create(this, "SpringBootLambda")
            .functionName("lra-observability")
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
                "OTEL_SERVICE_NAME", "lra-observability"
            ))
            .build();

        // ── API Gateway HTTP API — payload format v1.0 ───────────────────────
        HttpLambdaIntegration integration = new HttpLambdaIntegration(
            "LambdaIntegration",
            lambdaFn,
            HttpLambdaIntegrationProps.builder()
                .payloadFormatVersion(PayloadFormatVersion.VERSION_1_0)
                .build());

        HttpApi httpApi = HttpApi.Builder.create(this, "HttpApi")
            .apiName("lra-observability-api")
            .defaultIntegration(integration)
            .build();

        CfnOutput.Builder.create(this, "ApiUrl")
            .value(httpApi.getApiEndpoint())
            .description("API Gateway HTTP API endpoint")
            .build();
    }
}
