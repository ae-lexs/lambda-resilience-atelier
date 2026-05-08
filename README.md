# Lambda Resilience Atelier

> **Cold starts are a fail-slow failure mode for Java Lambdas** — slow but recoverable, visible to users at the moment of greatest demand, and addressable through a deliberate stack of mitigations whose cost-versus-effect curve must be measured rather than assumed.

This repository is the empirical companion to a public, modular codelab. Across eight modules it (1) establishes the Java 21 + Spring Boot cold-start baseline at **4,623 ms**, (2) reduces it to **1,703 ms** with SnapStart and to effectively zero with Provisioned Concurrency, (3) covers the parallel concurrency failure mode (database connection exhaustion) via Aurora Serverless v2 + RDS Proxy, (4) verifies the system holds under burst load (k6) and deliberate failure injection (AWS FIS), and (5) ships every future change through OIDC-federated CI/CD with no long-lived AWS credentials.

## Read first

**[`docs/adr/0001-architecture-and-decisions.md`](docs/adr/0001-architecture-and-decisions.md)** is the single-file architectural document — context, system architecture, all eleven decisions with their post-hoc consequences, the empirical findings table, the cold-start budget reduction journey, and six synthesis takeaways. ≈ 230 lines. Read this and you have walked the atelier.

## Run it yourself

The full long-form module walkthroughs (procedural steps, all CDK code, every CloudWatch Logs Insights query, the pitfalls catalogue) live in the companion `constellational_atelier` Obsidian vault under `Lambda Resilience Atelier/`. To deploy the baseline stack from a clean machine:

```bash
git clone https://github.com/ae-lexs/lambda-resilience-atelier.git
cd lambda-resilience-atelier
cp .env.example .env                             # set AWS_ACCOUNT_ID, AWS_REGION, AWS_PROFILE
aws sso login --profile "$(grep AWS_PROFILE .env | cut -d= -f2)"
docker compose build cdk
docker compose run --rm build ./gradlew :api:shadowJar --no-daemon
docker compose run --rm cdk cdk deploy LraBaselineStack --require-approval never
```

Nothing runs on the host except Docker and the AWS CLI. CDK CLI, Gradle, Java, and k6 all live in containers (`docker-compose.yml`).

## CI/CD

GitHub Actions + OIDC. PRs are gated on `Gradle test` + `CDK synth`; deploys are operator-triggered via `workflow_dispatch` from the Actions tab. No AWS access keys exist in GitHub Secrets — the trust path is documented in the ADR (Decision 11).

## License

MIT.
