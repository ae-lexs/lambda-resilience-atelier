package io.github.aelexs.api;

import com.amazonaws.serverless.exceptions.ContainerInitializationException;
import com.amazonaws.serverless.proxy.model.AwsProxyRequest;
import com.amazonaws.serverless.proxy.model.AwsProxyResponse;
import com.amazonaws.serverless.proxy.spring.SpringBootLambdaContainerHandler;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestStreamHandler;

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
    // The HTTP API integration pins `PayloadFormatVersion.VERSION_1_0` in BaselineStack
    // to match what this handler reads.
    //
    // The static initializer runs during Lambda's Init phase — this is where Spring's
    // application context is loaded and the cold start duration accrues. Warm invocations
    // skip this entirely; the cached handler reference handles the request directly.
    private static final SpringBootLambdaContainerHandler<AwsProxyRequest, AwsProxyResponse> handler;

    static {
        try {
            handler = SpringBootLambdaContainerHandler.getAwsProxyHandler(Application.class);
        } catch (ContainerInitializationException e) {
            throw new RuntimeException("Failed to initialize Spring context", e);
        }
    }

    @Override
    public void handleRequest(InputStream in, OutputStream out, Context ctx) throws IOException {
        handler.proxyStream(in, out, ctx);
    }
}
