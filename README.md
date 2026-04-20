# Lambda Resilience Atelier

A modular, hands-on codelab that makes Java Lambda cold start anatomy, concurrent initialization pressure, connection exhaustion, and chaos behavior observable as measurable phenomena on real AWS infrastructure.

**Thesis:** cold starts are a fail-slow failure mode for Java Lambdas. Each module establishes, measures, or mitigates a specific aspect of that failure mode.

---

## Architecture

A Java 21 + Spring Boot 3.x Lambda behind an API Gateway HTTP API, deployed inside a VPC with private isolated subnets and no NAT Gateway. Later modules add ADOT observability (Grafana Cloud), SnapStart, Provisioned Concurrency, Aurora Serverless v2 via RDS Proxy with IAM authentication, k6 load testing, and AWS Fault Injection Service chaos experiments.

Full architectural rationale — considered options, trade-offs, rejected alternatives, and the cost model — lives in [`docs/adr/0001-architecture-and-decisions.md`](docs/adr/0001-architecture-and-decisions.md).

---

## Prerequisites

- Docker 24+ with Compose v2 (`docker compose version`).
- AWS CLI v2 configured with SSO (`aws configure sso`).
- An AWS account you can deploy to. The AWS CDK must be bootstrapped in your target region (`cdk bootstrap aws://ACCOUNT_ID/us-east-1`).

Nothing else is installed on the host. Java, Gradle, Node.js, and the AWS CDK CLI all run inside containers.

---

## Setup

```bash
git clone https://github.com/ae-lexs/lambda-resilience-atelier.git
cd lambda-resilience-atelier

cp .env.example .env
# Edit .env and set AWS_ACCOUNT_ID, AWS_REGION, AWS_PROFILE

aws sso login --profile "$(grep AWS_PROFILE .env | cut -d= -f2)"

docker compose build cdk
```

---

## Repository Layout

```
lambda-resilience-atelier/
├── settings.gradle.kts          # Gradle multi-project root (added in scaffold)
├── build.gradle.kts             # shared Java 21 toolchain
├── api/                         # Spring Boot 3.x Lambda (REST API)
├── cdk/                         # AWS CDK v2 Java — App.java + stacks/ + constructs/
├── docker/
│   └── cdk.Dockerfile           # JDK 21 + Node 20 + aws-cdk CLI
├── docker-compose.yml           # build + cdk services
├── .env.example
├── docs/
│   └── adr/
│       └── 0001-architecture-and-decisions.md
└── README.md
```

New CDK stacks land in `cdk/src/main/java/com/example/infra/stacks/` and are wired into `App.java`. There are no module-scoped directories — modules are a learning progression, not a build-time partition.

---

## Typical Workflow

```bash
# Inspect the Gradle multi-project layout
docker compose run --rm build ./gradlew projects

# Build the Lambda shaded JAR (produces api/build/libs/api.jar)
docker compose run --rm build ./gradlew :api:shadowJar --no-daemon

# Synthesize the CDK app (produces cdk/cdk.out/)
docker compose run --rm cdk cdk synth

# Diff against the deployed stack
docker compose run --rm cdk cdk diff

# Deploy
docker compose run --rm cdk cdk deploy LraBaselineStack --require-approval never

# Tail CloudWatch logs from the host (needs AWS_PROFILE set or --profile)
aws logs tail /aws/lambda/lra-baseline --follow --profile "$AWS_PROFILE"

# Tear down
docker compose run --rm cdk cdk destroy --all --force
```

**CDK version note.** The `aws-cdk` npm CLI and the `aws-cdk-lib` Java library track independent version schemes in CDK v2. This repo pins CLI `2.1118.2` (in `docker/cdk.Dockerfile`) and library `2.250.0` (in `cdk/build.gradle.kts`). Wire compatibility between the two is governed by the cloud assembly schema, which is stable across current versions.

---

## Modules

| Module | Topic | Status |
|---|---|---|
| 01 | Baseline deployment — cold start measurement | In progress |
| 02 | Observability (ADOT + Grafana Cloud) | Pending |
| 03 | SnapStart | Pending |
| 04 | Provisioned Concurrency | Pending |
| 05 | RDS Proxy + Aurora Serverless v2 | Pending |
| 06 | Load testing (k6) | Pending |
| 07 | Chaos engineering (AWS FIS) | Pending |
| 08 | CI/CD (GitHub Actions OIDC) | Pending |
| 09 | Local development (Lambda RIE) | Pending |
| 10 | Teardown | Pending |

---

## Cost Discipline

Every module is designed to be destroyable. Total cost across all modules, run as separate ~4-hour sessions with `cdk destroy` at the end of each, is estimated under $5. See the cost model in the [ADR](docs/adr/0001-architecture-and-decisions.md#cost-model) for per-module breakdowns and formulas.

**Always run `cdk destroy` before ending a session.** VPC interface endpoints, Aurora Serverless v2, and RDS Proxy accrue cost while deployed even with zero traffic.

---

## License

MIT. See `LICENSE` (to be added).
