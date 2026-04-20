package io.github.aelexs.infra;

import io.github.aelexs.infra.stacks.BaselineStack;
import software.amazon.awscdk.App;
import software.amazon.awscdk.Environment;
import software.amazon.awscdk.StackProps;

public final class InfraApp {

    private InfraApp() {
        // Single-entry-point CDK application; not instantiated.
    }

    public static void main(final String[] args) {
        App app = new App();

        StackProps.Builder propsBuilder = StackProps.builder()
            .description("Lambda Resilience Atelier — Module 01 baseline (Spring Boot Lambda + HTTP API + PRIVATE_ISOLATED VPC).");

        // Bind to a concrete AWS environment only when both are set. Leaving the stack
        // environment-agnostic lets `cdk synth` run in CI or locally without credentials
        // while `cdk deploy` still pins the real account/region from .env.
        String account = System.getenv("CDK_DEFAULT_ACCOUNT");
        String region = System.getenv("CDK_DEFAULT_REGION");
        if (isSet(account) && isSet(region)) {
            propsBuilder.env(Environment.builder()
                .account(account)
                .region(region)
                .build());
        }

        new BaselineStack(app, "LraBaselineStack", propsBuilder.build());

        app.synth();
    }

    private static boolean isSet(final String value) {
        return value != null && !value.isBlank();
    }
}
