rootProject.name = "lambda-resilience-atelier"

// Subprojects are included as they are scaffolded. The Lambda application
// (Spring Boot REST API) and the AWS CDK v2 infrastructure are peer subprojects
// under the same multi-project build — see README.md, Decisions table rows 2 and 3.
include("api", "cdk")
