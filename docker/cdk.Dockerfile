# Image combining JDK 21 (to synthesize the CDK Java app) and Node.js 20 (to run
# the aws-cdk CLI). Built once, reused across all deploy/destroy/diff operations.
#
# Base: eclipse-temurin:21-jdk-jammy — Docker Official Image published by the Eclipse
# Foundation. Temurin is the upstream OpenJDK distribution that most Linux distros
# repackage. Node.js is layered on top via the NodeSource APT repository so the CLI
# version stays pinned.
#
# Pinned versions match docs/adr/0001-architecture-and-decisions.md Decisions 1, 3, 4.

FROM eclipse-temurin:21-jdk-jammy

ARG AWS_CDK_VERSION=2.1118.2
ARG NODE_MAJOR=20

RUN apt-get update -qq \
    && apt-get install -y -qq --no-install-recommends \
        ca-certificates \
        curl \
        gnupg \
    && mkdir -p /etc/apt/keyrings \
    && curl -fsSL https://deb.nodesource.com/gpgkey/nodesource-repo.gpg.key \
        | gpg --dearmor -o /etc/apt/keyrings/nodesource.gpg \
    && echo "deb [signed-by=/etc/apt/keyrings/nodesource.gpg] https://deb.nodesource.com/node_${NODE_MAJOR}.x nodistro main" \
        > /etc/apt/sources.list.d/nodesource.list \
    && apt-get update -qq \
    && apt-get install -y -qq --no-install-recommends nodejs \
    && rm -rf /var/lib/apt/lists/* \
    && npm install -g "aws-cdk@${AWS_CDK_VERSION}" --silent \
    && java -version \
    && node --version \
    && cdk --version

WORKDIR /workspace
