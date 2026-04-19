# Image combining JDK 21 (to synthesize the CDK Java app) and Node.js 20 (to run
# the aws-cdk CLI). Built once, reused across all deploy/destroy/diff operations.
#
# Pinned versions match docs/adr/0001-architecture-and-decisions.md Decisions 1, 3, 4.

FROM node:20-bookworm-slim

ARG AWS_CDK_VERSION=2.1118.2

RUN apt-get update -qq \
    && apt-get install -y -qq --no-install-recommends \
        openjdk-21-jdk-headless \
        ca-certificates \
        bash \
    && rm -rf /var/lib/apt/lists/* \
    && npm install -g "aws-cdk@${AWS_CDK_VERSION}" --silent \
    && cdk --version

WORKDIR /workspace
