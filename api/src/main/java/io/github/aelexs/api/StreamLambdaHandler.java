package io.github.aelexs.api;

import com.amazonaws.serverless.exceptions.ContainerInitializationException;
import com.amazonaws.serverless.proxy.model.AwsProxyRequest;
import com.amazonaws.serverless.proxy.model.AwsProxyResponse;
import com.amazonaws.serverless.proxy.spring.SpringBootLambdaContainerHandler;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestStreamHandler;
import io.github.aelexs.api.snapstart.InvokePrimingResource;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public class StreamLambdaHandler implements RequestStreamHandler {

    // Pet-store canonical pattern — https://github.com/aws/serverless-java-container/blob/main/samples/springboot3/pet-store/src/main/java/com/amazonaws/serverless/sample/springboot3/StreamLambdaHandler.java
    //
    // `getAwsProxyHandler` consumes `AwsProxyRequest` (API Gateway payload format v1.0).
    // The v2.0 factory (`getHttpApiV2ProxyHandler`) and the newer
    // `SpringDelegatingLambdaContainerHandler` both have known init-time
    // ClassCastExceptions at this library version (2.1.5) paired with Spring Boot 3.4.1.
    // The HTTP API integration pins `PayloadFormatVersion.VERSION_1_0` in SnapStartStack
    // to match what this handler reads.
    //
    // The static initializer runs during Lambda's Init phase. With SnapStart enabled,
    // this entire init runs *once* at version-publish time — the resulting JVM state
    // (Spring context, ADOT agent, JIT-compiled methods) is captured in the Firecracker
    // microVM snapshot and reused for every restored execution environment. Warm
    // invocations do not re-run this block; cold restores skip it entirely.
    private static final SpringBootLambdaContainerHandler<AwsProxyRequest, AwsProxyResponse> handler;

    // Strong reference for the SnapStart invoke-priming resource. CRaC's global context
    // holds only a WeakReference to registered Resources, so an unreferenced
    // `new InvokePrimingResource(...)` would be eligible for GC before checkpoint runs
    // and the hooks would silently never fire. Keeping the reference in a static field
    // ties its lifetime to the JVM, including across snapshot/restore.
    @SuppressWarnings("unused")
    private static final InvokePrimingResource PRIMING_RESOURCE;

    static {
        try {
            handler = SpringBootLambdaContainerHandler.getAwsProxyHandler(Application.class);
        } catch (ContainerInitializationException e) {
            throw new RuntimeException("Failed to initialize Spring context", e);
        }
        PRIMING_RESOURCE = new InvokePrimingResource(handler);
    }

    @Override
    public void handleRequest(InputStream in, OutputStream out, Context ctx) throws IOException {
        handler.proxyStream(in, out, ctx);
    }
}
