package io.github.aelexs.infra;

import io.github.aelexs.infra.stacks.BaselineStack;
import io.github.aelexs.infra.stacks.ObservabilityStack;
import io.github.aelexs.infra.stacks.SnapStartStack;
import io.github.aelexs.infra.stacks.ProvisionedConcurrencyStack;

import software.amazon.awscdk.App;
import software.amazon.awscdk.Environment;
import software.amazon.awscdk.StackProps;

public final class InfraApp {

    private InfraApp() {}

    public static void main(final String[] args) {
        App app = new App();

        String account = System.getenv("CDK_DEFAULT_ACCOUNT");
        String region  = System.getenv("CDK_DEFAULT_REGION");

        StackProps baselineProps;
        StackProps observabilityProps;
        StackProps snapStartProps;
        StackProps provisionedConcurrencyProps;

        if (isSet(account) && isSet(region)) {
            Environment env = Environment.builder()
                .account(account)
                .region(region)
                .build();
            baselineProps = StackProps.builder()
                .description("Lambda Resilience Atelier — Module 01 baseline.")
                .env(env)
                .build();
            observabilityProps = StackProps.builder()
                .description("Lambda Resilience Atelier — Module 02 observability (ADOT + X-Ray).")
                .env(env)
                .build();
            snapStartProps = StackProps.builder()
                .description("Lambda Resilience Atelier — Module 03 snapstart.")
                .env(env)
                .build();
            provisionedConcurrencyProps = StackProps.builder()
                .description("Lambda Resilience Atelier — Module 04 provisioned concurrency.")
                .env(env)
                .build();
        } else {
            baselineProps = StackProps.builder()
                .description("Lambda Resilience Atelier — Module 01 baseline.")
                .build();
            observabilityProps = StackProps.builder()
                .description("Lambda Resilience Atelier — Module 02 observability (ADOT + X-Ray).")
                .build();
            snapStartProps = StackProps.builder()
                .description("Lambda Resilience Atelier — Module 03 snapstart.")
                .build();
            provisionedConcurrencyProps = StackProps.builder()
                .description("Lambda Resilience Atelier — Module 04 provisioned concurrency.")
                .build();
        }

        new BaselineStack(app, "LraBaselineStack", baselineProps);
        new ObservabilityStack(app, "LraObservabilityStack", observabilityProps);
        new SnapStartStack(app, "LraSnapStartStack", snapStartProps);
        new ProvisionedConcurrencyStack(app, "LraProvisionedConcurrencyStack", provisionedConcurrencyProps);

        app.synth();
    }

    private static boolean isSet(final String value) {
        return value != null && !value.isBlank();
    }
}
