package io.github.aelexs.infra.stacks;

import software.amazon.awscdk.CfnOutput;
import software.amazon.awscdk.Duration;
import software.amazon.awscdk.RemovalPolicy;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.services.apigatewayv2.HttpApi;
import software.amazon.awscdk.aws_apigatewayv2_integrations.HttpLambdaIntegration;
import software.amazon.awscdk.services.ec2.InterfaceVpcEndpointAwsService;
import software.amazon.awscdk.services.ec2.InterfaceVpcEndpointOptions;
import software.amazon.awscdk.services.ec2.IpAddresses;
import software.amazon.awscdk.services.ec2.SubnetConfiguration;
import software.amazon.awscdk.services.ec2.SubnetSelection;
import software.amazon.awscdk.services.ec2.SubnetType;
import software.amazon.awscdk.services.ec2.Vpc;
import software.amazon.awscdk.services.lambda.Code;
import software.amazon.awscdk.services.lambda.Function;
import software.amazon.awscdk.services.lambda.LoggingFormat;
import software.amazon.awscdk.services.lambda.Runtime;
import software.amazon.awscdk.services.logs.LogGroup;
import software.amazon.awscdk.services.logs.RetentionDays;
import software.constructs.Construct;

import java.util.List;

public class BaselineStack extends Stack {

    public BaselineStack(final Construct scope, final String id, final StackProps props) {
        super(scope, id, props);

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

        vpc.addInterfaceEndpoint("CloudWatchLogsEndpoint",
            InterfaceVpcEndpointOptions.builder()
                .service(InterfaceVpcEndpointAwsService.CLOUDWATCH_LOGS)
                .build());

        // Explicit log group with DESTROY removal policy — by default CDK leaves the
        // Lambda-generated log group in place on `cdk destroy`. Binding the log group
        // to the stack lifecycle keeps teardown clean for repeated cold-start sessions.
        LogGroup logGroup = LogGroup.Builder.create(this, "LambdaLogGroup")
            .logGroupName("/aws/lambda/lra-baseline")
            .retention(RetentionDays.ONE_WEEK)
            .removalPolicy(RemovalPolicy.DESTROY)
            .build();

        Function lambdaFn = Function.Builder.create(this, "SpringBootLambda")
            .functionName("lra-baseline")
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
            .build();

        HttpLambdaIntegration integration =
            new HttpLambdaIntegration("LambdaIntegration", lambdaFn);

        HttpApi httpApi = HttpApi.Builder.create(this, "HttpApi")
            .apiName("lra-baseline-api")
            .defaultIntegration(integration)
            .build();

        CfnOutput.Builder.create(this, "ApiUrl")
            .value(httpApi.getApiEndpoint())
            .description("API Gateway HTTP API endpoint")
            .build();
    }
}
