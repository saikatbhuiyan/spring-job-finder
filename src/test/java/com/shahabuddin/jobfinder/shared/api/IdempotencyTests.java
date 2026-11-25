package com.shahabuddin.jobfinder.shared.api;

import com.shahabuddin.jobfinder.shared.Idempotent;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.client.EntityExchangeResult;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Locks in idempotency keys (docs/adr/0014-idempotency-keys.md) over real HTTP: a retry with the same
 * key and request is answered with the stored response and never executed again; the key with another
 * request is a 422; a retry during execution a 409; malformed or missing keys a 400; only 2xx responses
 * are kept. Each test uses fresh keys; executions are counted as differences.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "jobfinder.idempotency.max-request-size=1KB")
@AutoConfigureRestTestClient
class IdempotencyTests {

    private static final String DOCS = "https://github.com/saikatbhuiyan/spring-job-finder/blob/main/docs/problems.md#";
    private static final String BOOK = "{\"item\":\"book\"}";

    @Autowired
    private RestTestClient client;

    @Autowired
    private OrderProbeController orders;

    @Autowired
    private MeterRegistry meters;

    @LocalServerPort
    private int port;

    @Test
    void aRetryWithTheSameKeyAndRequestGetsTheFirstResponseWithoutExecutingAgain() {
        String key = newKey();
        int before = orders.created.get();
        double replayedBefore = outcomes("replayed");

        EntityExchangeResult<byte[]> first = post("/orders", key, BOOK).expectStatus().isCreated().expectBody().returnResult();
        EntityExchangeResult<byte[]> retry = post("/orders", key, BOOK).expectStatus().isCreated().expectBody().returnResult();

        assertThat(orders.created.get() - before).as("executions").isOne();
        assertThat(retry.getResponseBodyContent()).isEqualTo(first.getResponseBodyContent());
        assertThat(retry.getResponseHeaders().getLocation()).isEqualTo(first.getResponseHeaders().getLocation()).isNotNull();
        assertThat(retry.getResponseHeaders().getContentType()).isEqualTo(first.getResponseHeaders().getContentType());
        assertThat(first.getResponseHeaders().getFirst("Idempotent-Replayed")).isNull();
        assertThat(retry.getResponseHeaders().getFirst("Idempotent-Replayed")).isEqualTo("true");
        // The retry is its own request: its own trace ID, not the stored one.
        assertThat(retry.getResponseHeaders().getFirst("X-Trace-Id")).isNotEqualTo(first.getResponseHeaders().getFirst("X-Trace-Id"));
        assertThat(outcomes("replayed") - replayedBefore).isOne();
    }

    @Test
    void theKeyWithADifferentRequestIs422() {
        String key = newKey();
        post("/orders", key, BOOK).expectStatus().isCreated();
        int before = orders.created.get();

        for (var other : List.of(new String[]{"/orders", "{\"item\":\"pen\"}"}, new String[]{"/notes", BOOK})) {
            post(other[0], key, other[1])
                    .expectStatus().isEqualTo(422)
                    .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                    .expectBody()
                    .jsonPath("$.type").isEqualTo(DOCS + "idempotency-key-reused")
                    .jsonPath("$.title").isEqualTo("Idempotency key reused")
                    .jsonPath("$.instance").isEqualTo("/api/v1/idempotency-probe" + other[0])
                    .jsonPath("$.traceId").isNotEmpty();
        }
        assertThat(orders.created.get()).isEqualTo(before);
    }

    @Test
    void anIdempotentEndpointRequiresAKey() {
        int before = orders.created.get();

        client.post().uri("/api/v1/idempotency-probe/orders").contentType(MediaType.APPLICATION_JSON).body(BOOK)
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.type").isEqualTo(DOCS + "validation-error")
                .jsonPath("$.errors[0].parameter").isEqualTo("Idempotency-Key")
                .jsonPath("$.errors[0].code").isEqualTo("Required");
        assertThat(orders.created.get()).isEqualTo(before);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "too-short-key",                                       // fewer than 16 characters
            "\"8e03978e-40d5-43e8-bc93-6894a57f9324",              // no closing quote
            "\"8e03978e-40d5-43e8-bc93-6894a57f9324\";a=1",        // a parameter after the string
            "\"8e03978e-40d5-43e8\\x-bc93-6894a57f9324\"",          // an escape other than \" and \\
            "8e03978e 40d5 43e8 bc93 6894a57f9324",                // unquoted with spaces
            "8e03978e-40d5-43e8-bc93-6894a57f9324,second"})        // a list, not one key
    void aMalformedKeyIsRejectedNotIgnored(String key) {
        // Ignoring it would execute every retry the client believes is protected.
        int before = orders.created.get();

        for (String path : List.of("/orders", "/notes")) {
            post(path, key, BOOK)
                    .expectStatus().isBadRequest()
                    .expectBody()
                    .jsonPath("$.errors[0].parameter").isEqualTo("Idempotency-Key")
                    .jsonPath("$.errors[0].code").isEqualTo("InvalidValue");
        }
        assertThat(orders.created.get()).isEqualTo(before);
    }

    @Test
    void keysLongerThan255CharactersAreRejected() {
        post("/orders", "k".repeat(256), BOOK).expectStatus().isBadRequest();
        post("/orders", newKey() + "k".repeat(255 - 36), BOOK).expectStatus().isCreated();
    }

    @Test
    void twoKeyHeadersAreRejected() {
        client.post().uri("/api/v1/idempotency-probe/orders").contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", newKey(), newKey()).body(BOOK)
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void theQuotedStructuredFieldAndTheBareFormAreTheSameKey() {
        String key = newKey();
        post("/orders", "\"" + key + "\"", BOOK).expectStatus().isCreated();

        post("/orders", key, BOOK).expectStatus().isCreated().expectHeader().valueEquals("Idempotent-Replayed", "true");
    }

    @Test
    void aRetryWhileTheFirstIsStillRunningIs409AndThenGetsItsResult() throws Exception {
        String key = newKey();
        orders.slowStarted = new CountDownLatch(1);
        orders.slowMayFinish = new CountDownLatch(1);
        int before = orders.created.get();

        CompletableFuture<Integer> first = CompletableFuture.supplyAsync(() ->
                post("/slow-orders", key, BOOK).returnResult().getStatus().value());
        assertThat(orders.slowStarted.await(10, TimeUnit.SECONDS)).isTrue();

        post("/slow-orders", key, BOOK)
                .expectStatus().isEqualTo(409)
                .expectHeader().valueEquals("Retry-After", "1")
                .expectBody()
                .jsonPath("$.type").isEqualTo(DOCS + "request-in-progress");

        orders.slowMayFinish.countDown();
        assertThat(first.get(10, TimeUnit.SECONDS)).isEqualTo(201);
        post("/slow-orders", key, BOOK).expectStatus().isCreated().expectHeader().valueEquals("Idempotent-Replayed", "true");
        assertThat(orders.created.get() - before).isOne();
    }

    @Test
    void concurrentDuplicatesExecuteExactlyOnce() throws Exception {
        // A double-clicked button, or a client retrying in parallel.
        String key = newKey();
        int before = orders.created.get();
        CountDownLatch go = new CountDownLatch(1);
        List<CompletableFuture<EntityExchangeResult<byte[]>>> responses = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(16)) {
            for (int i = 0; i < 16; i++) {
                responses.add(CompletableFuture.supplyAsync(() -> {
                    try {
                        go.await();
                    } catch (InterruptedException e) {
                        throw new IllegalStateException(e);
                    }
                    return post("/orders", key, BOOK).expectBody().returnResult();
                }, pool));
            }
            go.countDown();
            CompletableFuture.allOf(responses.toArray(CompletableFuture[]::new)).get(30, TimeUnit.SECONDS);
        }

        assertThat(orders.created.get() - before).as("executions").isOne();
        List<EntityExchangeResult<byte[]>> results = responses.stream().map(CompletableFuture::join).toList();
        assertThat(results).filteredOn(r -> r.getStatus().value() == 201 && r.getResponseHeaders().getFirst("Idempotent-Replayed") == null)
                .hasSize(1);
        assertThat(results).allSatisfy(r -> assertThat(r.getStatus().value()).isIn(201, 409));
    }

    @Test
    void aServerErrorFreesTheKeySoTheRetryCanSucceed() {
        String key = newKey();
        orders.failuresLeft.set(1);
        int before = orders.created.get();

        post("/orders", key, BOOK).expectStatus().isEqualTo(500);
        post("/orders", key, BOOK).expectStatus().isCreated().expectHeader().doesNotExist("Idempotent-Replayed");
        assertThat(orders.created.get() - before).isOne();
    }

    @Test
    void anExceptionFromALaterFilterFreesTheKeyToo() {
        // Not a 5xx status seen by the idempotency filter, but an exception passing through it (it is
        // answered later, by TraceIdFilter). Left claimed, the key would answer 409 for 24 hours.
        String key = newKey();
        int before = orders.created.get();

        client.post().uri("/api/v1/idempotency-probe/orders").contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", key).header(FailingFilter.TRIGGER, "yes").body(BOOK)
                .exchange()
                .expectStatus().isEqualTo(500);
        post("/orders", key, BOOK).expectStatus().isCreated().expectHeader().doesNotExist("Idempotent-Replayed");
        assertThat(orders.created.get() - before).isOne();
    }

    @Test
    void aClientErrorFreesTheKeySoTheCorrectedRequestCanUseIt() {
        String key = newKey();

        post("/orders", key, "{\"item\":\"\"}").expectStatus().isBadRequest();
        // Not a 422 "key reused": the rejected request changed nothing.
        post("/orders", key, BOOK).expectStatus().isCreated().expectHeader().doesNotExist("Idempotent-Replayed");
    }

    @Test
    void withoutTheAnnotationAKeyIsOptionalButHonoured() {
        int before = orders.created.get();
        noKeyPost("/notes").expectStatus().isCreated();
        noKeyPost("/notes").expectStatus().isCreated();
        assertThat(orders.created.get() - before).as("no key: every request executes").isEqualTo(2);

        String key = newKey();
        post("/notes", key, BOOK).expectStatus().isCreated();
        post("/notes", key, BOOK).expectStatus().isCreated().expectHeader().valueEquals("Idempotent-Replayed", "true");
        assertThat(orders.created.get() - before).isEqualTo(3);
    }

    @Test
    void aBodyLargerThanTheLimitIs413() {
        // The body is buffered to fingerprint it (limit 1KB in this test).
        String large = "{\"item\":\"" + "x".repeat(2048) + "\"}";
        int before = orders.created.get();

        post("/orders", newKey(), large)
                .expectStatus().isEqualTo(413)
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.title").isEqualTo("Content Too Large");
        assertThat(orders.created.get()).isEqualTo(before);
    }

    @Test
    void aChunkedBodyLargerThanTheLimitIs413Too() throws Exception {
        // No Content-Length to check up front: the limit must hold while reading.
        byte[] large = ("{\"item\":\"" + "x".repeat(2048) + "\"}").getBytes(StandardCharsets.UTF_8);
        int before = orders.created.get();
        try (HttpClient http = HttpClient.newHttpClient()) {
            HttpResponse<String> response = http.send(HttpRequest.newBuilder(
                            URI.create("http://localhost:" + port + "/api/v1/idempotency-probe/orders"))
                    .header("Content-Type", "application/json")
                    .header("Idempotency-Key", newKey())
                    .POST(HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(large)))
                    .build(), HttpResponse.BodyHandlers.ofString());

            assertThat(response.statusCode()).isEqualTo(413);
        }
        assertThat(orders.created.get()).isEqualTo(before);
    }

    private RestTestClient.ResponseSpec post(String path, String key, String body) {
        return client.post().uri("/api/v1/idempotency-probe" + path)
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", key)
                .body(body)
                .exchange();
    }

    private RestTestClient.ResponseSpec noKeyPost(String path) {
        return client.post().uri("/api/v1/idempotency-probe" + path).contentType(MediaType.APPLICATION_JSON).body(BOOK).exchange();
    }

    private static String newKey() {
        return UUID.randomUUID().toString();
    }

    private double outcomes(String outcome) {
        return meters.get("jobfinder.idempotency.requests").tag("outcome", outcome).counter().count();
    }

    // --- Test-only endpoints ---------------------------------------------------------------------

    @TestConfiguration
    static class ProbeConfig {
        @Bean
        OrderProbeController orderProbeController() {
            return new OrderProbeController();
        }

        @Bean
        FilterRegistrationBean<FailingFilter> failingFilter() {
            FilterRegistrationBean<FailingFilter> registration = new FilterRegistrationBean<>(new FailingFilter());
            registration.setOrder(IdempotencyConfig.ORDER + 1);
            return registration;
        }
    }

    /** A filter after the idempotency filter that throws when asked to (a header outside the fingerprint). */
    static class FailingFilter extends OncePerRequestFilter {

        static final String TRIGGER = "X-Fail-In-Filter";

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            if (request.getHeader(TRIGGER) != null) {
                throw new IllegalStateException("filter failed");
            }
            chain.doFilter(request, response);
        }
    }

    record OrderRequest(@NotBlank String item) {
    }

    record OrderResponse(int id, String item) {
    }

    @RestController
    @RequestMapping("/api/{version}/idempotency-probe")
    static class OrderProbeController {

        final AtomicInteger created = new AtomicInteger();
        final AtomicInteger failuresLeft = new AtomicInteger();
        volatile CountDownLatch slowStarted = new CountDownLatch(0);
        volatile CountDownLatch slowMayFinish = new CountDownLatch(0);

        @Idempotent
        @PostMapping(path = "/orders", version = "1")
        ResponseEntity<OrderResponse> create(@Valid @RequestBody OrderRequest request) {
            if (failuresLeft.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
                throw new IllegalStateException("database unavailable");
            }
            return created(request);
        }

        @Idempotent
        @PostMapping(path = "/slow-orders", version = "1")
        ResponseEntity<OrderResponse> createSlowly(@Valid @RequestBody OrderRequest request) throws InterruptedException {
            slowStarted.countDown();
            slowMayFinish.await(10, TimeUnit.SECONDS);
            return created(request);
        }

        /** Not annotated: a key is optional. */
        @PostMapping(path = "/notes", version = "1")
        ResponseEntity<OrderResponse> note(@Valid @RequestBody OrderRequest request) {
            return created(request);
        }

        private ResponseEntity<OrderResponse> created(OrderRequest request) {
            int id = created.incrementAndGet();
            return ResponseEntity.created(URI.create("/api/v1/idempotency-probe/orders/" + id))
                    .body(new OrderResponse(id, request.item()));
        }
    }
}
