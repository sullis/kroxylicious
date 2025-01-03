/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */

package io.kroxylicious.kms.provider.aws.kms;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import io.kroxylicious.kms.provider.aws.kms.credentials.LongTermCredentialsProvider;

import edu.umd.cs.findbugs.annotations.NonNull;

import static org.assertj.core.api.Assertions.assertThat;

class AwsV4SigningHttpRequestBuilderTest {

    private static final YAMLFactory YAML_FACTORY = new YAMLFactory();
    private static final ObjectMapper MAPPER = new ObjectMapper(YAML_FACTORY).registerModule(new JavaTimeModule());
    private static final String ACCESS_KEY = "access";
    private static final String SECRET_KEY = "secret";
    private static final String REGION = "us-east-1";
    private static final String SERVICE = "kms";
    public static final URI TEST_URI = URI.create("http://localhost:1234");

    static Stream<Arguments> requestSigning() throws Exception {
        try (var knownGoodYaml = AwsV4SigningHttpRequestBuilderTest.class.getResourceAsStream("/io/kroxylicious/kms/provider/aws/kms/known_good.yaml")) {
            assertThat(knownGoodYaml).isNotNull();
            var parser = YAML_FACTORY.createParser(knownGoodYaml);
            List<TestDef> testDefs = MAPPER.readValues(parser, TestDef.class).readAll();
            return testDefs.stream().map(td -> Arguments.of(td.testName(), td));
        }
    }

    /**
     * The test compares known-good signatures generated by Curl's AWS v4 signing
     * support with the signature resulting from signing the same request with
     * AwsV4SigningHttpRequestBuilder.
     *
     * @param testName test name
     * @param testDef test definition
     * @throws Exception exception
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource
    void requestSigning(String testName, TestDef testDef) throws Exception {

        var requestHeaderCatching = new RequestHeaderCatchingHandler();
        var server = httpServer(testDef.url.getPort(), requestHeaderCatching);
        var client = HttpClient.newHttpClient();
        try {
            var builder = AwsV4SigningHttpRequestBuilder.newBuilder(
                    LongTermCredentialsProvider.fixedCredentials(testDef.accessKeyId, testDef.secretAccessKey()), testDef.region(),
                    testDef.service(),
                    testDef.requestTime());
            testDef.apply(builder);
            var request = builder.build();

            client.send(request, HttpResponse.BodyHandlers.discarding());
            var actualHeaders = requestHeaderCatching.getHeaders();

            assertThat(actualHeaders)
                    .containsAllEntriesOf(testDef.expectedHeaders());
        }
        finally {
            server.stop(0);
        }
    }

    @Test
    void copy() {
        var original = createBuilder(TEST_URI);
        var copy = original.copy();
        assertThat(copy)
                .isNotEqualTo(original)
                .isInstanceOf(original.getClass());

        var req = copy.build();
        assertThat(req.uri()).isEqualTo(TEST_URI);
    }

    @ParameterizedTest
    @ValueSource(booleans = { true, false })
    void expectContinue(Boolean expectContinue) {
        var r = createBuilder(TEST_URI).expectContinue(expectContinue).build();
        assertThat(r.expectContinue()).isEqualTo(expectContinue);
    }

    @ParameterizedTest
    @EnumSource(value = HttpClient.Version.class)
    void version(HttpClient.Version version) {
        var req = createBuilder(TEST_URI).version(version).build();
        assertThat(req.version()).contains(version);
    }

    @Test
    void timeout() {
        var duration = Duration.ofMinutes(1);
        var req = createBuilder(TEST_URI).timeout(duration).build();
        assertThat(req.timeout()).contains(duration);
    }

    @Test
    void setHeader() {
        var req = createBuilder(TEST_URI).setHeader("foo", "bar").build();
        assertThat(req.headers().map()).containsEntry("foo", List.of("bar"));
    }

    @Test
    void headers() {
        var req = createBuilder(TEST_URI).headers("foo", "bar", "coo", "car").build();
        assertThat(req.headers().map())
                .containsEntry("foo", List.of("bar"))
                .containsEntry("coo", List.of("car"));
    }

    @ParameterizedTest
    @ValueSource(strings = { "GET", "POST", "PUT", "DELETE" })
    void methodCall(String method) {
        var req = createBuilder(TEST_URI).method(method, HttpRequest.BodyPublishers.noBody()).build();
        assertThat(req.method()).isEqualTo(method);
    }

    @ParameterizedTest
    @ValueSource(strings = { "GET", "POST", "PUT", "DELETE" })
    void method(String method) {
        var builder = createBuilder(TEST_URI);

        switch (method) {
            case "GET":
                builder.GET();
                break;
            case "DELETE":
                builder.DELETE();
                break;
            case "POST":
                builder.POST(HttpRequest.BodyPublishers.noBody());
                break;
            case "PUT":
                builder.PUT(HttpRequest.BodyPublishers.noBody());
                break;
        }
        assertThat(builder.build().method()).isEqualTo(method);
    }

    static Stream<Arguments> hostHeader() {
        return Stream.of(
                Arguments.of("http implicit port", URI.create("http://localhost/foo"), "localhost"),
                Arguments.of("http explicit default port", URI.create("http://localhost/foo:80"), "localhost"),
                Arguments.of("https implicit port", URI.create("https://localhost/foo"), "localhost"),
                Arguments.of("https explicit default port", URI.create("https://localhost:443/foo"), "localhost"),
                Arguments.of("http non standard port", URI.create("http://localhost:8080/foo"), "localhost:8080"),
                Arguments.of("https non standard port", URI.create("http://localhost:8443/foo"), "localhost:8443"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource
    void hostHeader(String name, URI uri, String expected) {
        var builder = ((AwsV4SigningHttpRequestBuilder) createBuilder(TEST_URI));
        assertThat(builder.getHostHeaderForSigning(uri)).isEqualTo(expected);
    }

    @NonNull
    private HttpRequest.Builder createBuilder(URI uri) {
        var builder = AwsV4SigningHttpRequestBuilder.newBuilder(LongTermCredentialsProvider.fixedCredentials(ACCESS_KEY, SECRET_KEY),
                REGION, SERVICE, Instant.ofEpochMilli(0));
        if (uri != null) {
            builder.uri(uri);
        }
        return builder;
    }

    record TestDef(String testName, Instant requestTime, URI url, String method, String accessKeyId, String secretAccessKey, String region, String service,
                   String data, Map<String, List<String>> headers, Map<String, List<String>> expectedHeaders) {
        public void apply(HttpRequest.Builder builder) {
            builder.uri(url());
            if (headers != null) {
                headers.forEach((name, valueList) -> valueList.forEach(value -> builder.header(name, value)));
            }
            switch (method()) {
                case "POST":
                    builder.POST(data == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(data()));
                    break;
                case "GET":
                    builder.GET();
                    break;
                default:
                    throw new UnsupportedOperationException(method() + " is not supported.");
            }
        }
    }

    private static HttpServer httpServer(int port, HttpHandler handler) {
        try {
            var server = HttpServer.create(new InetSocketAddress(port), 0);
            server.createContext("/", handler);
            server.start();
            return server;
        }
        catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static class RequestHeaderCatchingHandler implements HttpHandler {
        private Headers headers;

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            headers = exchange.getRequestHeaders();
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().close();
        }

        public Headers getHeaders() {
            return headers;
        }
    }
}
