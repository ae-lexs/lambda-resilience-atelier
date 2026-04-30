package io.github.aelexs.api.snapstart;

import com.amazonaws.serverless.proxy.model.AwsProxyRequest;
import com.amazonaws.serverless.proxy.model.AwsProxyResponse;
import com.amazonaws.serverless.proxy.spring.SpringBootLambdaContainerHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.crac.Context;
import org.crac.Core;
import org.crac.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Map;

/**
 * Invoke priming for SnapStart. During the beforeCheckpoint phase, dispatches a
 * synthetic GET /health request through the same SpringBootLambdaContainerHandler
 * that serves real traffic. HotSpot JIT-compiles the request path, Jackson builds
 * and caches its bean serializers, and Spring MVC fully initializes the dispatcher
 * servlet — all before the snapshot is taken. Restored execution environments
 * then start with this warm state already in place.
 *
 * Reference: AWS Compute Blog — Optimizing cold start performance of AWS Lambda
 * using advanced priming strategies with SnapStart.
 * https://aws.amazon.com/blogs/compute/optimizing-cold-start-performance-of-aws-lambda-using-advanced-priming-strategies-with-snapstart/
 */
public final class InvokePrimingResource implements Resource {

    private static final Logger LOG = LoggerFactory.getLogger(InvokePrimingResource.class);

    // Hold the inner SpringBoot container handler directly, not the outer
    // RequestStreamHandler wrapper. The wrapper exists for the Lambda runtime;
    // the priming path needs the type-aware proxyStream(InputStream, OutputStream,
    // Context) call and gains nothing from going through the wrapper indirection.
    private final SpringBootLambdaContainerHandler<AwsProxyRequest, AwsProxyResponse> handler;
    private final ObjectMapper mapper = new ObjectMapper();

    public InvokePrimingResource(
        final SpringBootLambdaContainerHandler<AwsProxyRequest, AwsProxyResponse> handler
    ) {
        this.handler = handler;
        // Register on construction. The caller (StreamLambdaHandler.PRIMING_RESOURCE)
        // must retain a strong reference to this instance — Core.getGlobalContext()
        // holds a WeakReference and will not prevent garbage collection on its own.
        Core.getGlobalContext().register(this);
    }

    @Override
    public void beforeCheckpoint(final Context<? extends Resource> ctx) throws Exception {
        LOG.info("SnapStart beforeCheckpoint — priming /health");

        // API Gateway HTTP API v1.0 event for GET /health. Same payload shape
        // API Gateway delivers at runtime, so the priming call exercises the
        // same dispatch path real traffic will hit on first restore.
        Map<String, Object> primingEvent = Map.of(
            "version", "1.0",
            "httpMethod", "GET",
            "path", "/health",
            "resource", "/health",
            "headers", Map.of("Host", "snapstart-priming.local"),
            "requestContext", Map.of(
                "httpMethod", "GET",
                "path", "/health",
                "stage", "$default",
                "requestId", "snapstart-priming"
            ),
            "body", "",
            "isBase64Encoded", false
        );

        byte[] eventBytes = mapper.writeValueAsBytes(primingEvent);
        try (ByteArrayInputStream in = new ByteArrayInputStream(eventBytes);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            // Same proxyStream call StreamLambdaHandler.handleRequest delegates
            // to. Lambda Context is null because no real invocation context
            // exists at checkpoint time — Spring handlers do not consult it
            // for /health.
            handler.proxyStream(in, out, null);
        }

        LOG.info("SnapStart beforeCheckpoint — priming complete");
    }

    @Override
    public void afterRestore(final Context<? extends Resource> ctx) throws Exception {
        LOG.info("SnapStart afterRestore — environment hydrated");
        // No work needed for this codelab — Spring Boot's auto-managed lifecycle
        // restarts framework-managed resources, and there are no manual
        // connection pools yet. Module 05 (database resilience) re-initializes
        // the Aurora connection pool here.
    }
}
