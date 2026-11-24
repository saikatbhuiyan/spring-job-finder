package com.shahabuddin.jobfinder.shared.api;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.core.task.TaskExecutor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.client.EntityExchangeResult;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Locks in request correlation (docs/adr/0013-request-correlation.md) over real HTTP: one trace ID per
 * request, the same in the response header, in every log line of the request and in Problem Details;
 * only a valid W3C {@code traceparent} is accepted from callers; nothing but {@code traceparent} is
 * passed on to downstream calls; async work stays in the trace.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
@ExtendWith(OutputCaptureExtension.class)
class TracingTests {

    private static final String TRACE_ID = "[0-9a-f]{32}";
    private static final String CALLER_TRACE_ID = "0af7651916cd43dd8448eb211c80319c";

    @Autowired
    private RestTestClient client;

    @Test
    void everyResponseCarriesItsTraceIdAndEveryLogLineOfTheRequestHasIt(CapturedOutput output) {
        String first = traceIdOf(client.get().uri("/api/v1/trace-probe/log").exchange());
        String second = traceIdOf(client.get().uri("/api/v1/trace-probe/log").exchange());

        assertThat(first).matches(TRACE_ID).isNotEqualTo(second);
        assertThat(output.getAll().lines().filter(line -> line.contains("trace probe handled")))
                .anySatisfy(line -> assertThat(line).contains(first))
                .anySatisfy(line -> assertThat(line).contains(second));
    }

    @Test
    void actuatorResponsesCarryATraceIdToo() {
        assertThat(traceIdOf(client.get().uri("/actuator/health").exchange())).matches(TRACE_ID);
    }

    @Test
    void aValidTraceparentContinuesTheCallersTrace() {
        // A gateway or another service that already started the trace.
        String traceId = traceIdOf(client.get().uri("/api/v1/trace-probe/log")
                .header("traceparent", "00-" + CALLER_TRACE_ID + "-b7ad6b7169203331-01")
                .exchange());

        assertThat(traceId).isEqualTo(CALLER_TRACE_ID);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "00-ZZZZ651916cd43dd8448eb211c80319c-b7ad6b7169203331-01",          // not hex
            "00-00000000000000000000000000000000-b7ad6b7169203331-01",          // all-zero trace ID
            "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01 INFO x",   // trailing text
            "0af7651916cd43dd8448eb211c80319c"})                                // a bare ID
    void anInvalidTraceparentStartsANewTrace(String traceparent) {
        String traceId = traceIdOf(client.get().uri("/api/v1/trace-probe/log").header("traceparent", traceparent).exchange());

        assertThat(traceId).matches(TRACE_ID).isNotEqualTo(CALLER_TRACE_ID).isNotEqualTo("0".repeat(32));
    }

    @Test
    void b3HeadersAreIgnored() {
        // Boot accepts B3 by default; only W3C is configured.
        String traceId = traceIdOf(client.get().uri("/api/v1/trace-probe/log")
                .header("b3", "80f198ee56343ba864fe8b2a57d3eff7-e457b5a2e4d86bd1-1")
                .header("X-B3-TraceId", "80f198ee56343ba864fe8b2a57d3eff7")
                .header("X-B3-SpanId", "e457b5a2e4d86bd1")
                .exchange());

        assertThat(traceId).matches(TRACE_ID).isNotEqualTo("80f198ee56343ba864fe8b2a57d3eff7");
    }

    @Test
    void onlyTraceparentIsPassedOnToDownstreamCallsNeverTheClientsBaggage() {
        // What an instrumented RestClient would add to an outgoing request made while handling this one.
        // By default the client's baggage was forwarded verbatim.
        EntityExchangeResult<Map<String, String>> result = client.get().uri("/api/v1/trace-probe/downstream-headers")
                .header("traceparent", "00-" + CALLER_TRACE_ID + "-b7ad6b7169203331-01")
                .header("baggage", "user.role=admin,secret=hunter2")
                .exchange()
                .expectStatus().isOk()
                .expectBody(new org.springframework.core.ParameterizedTypeReference<Map<String, String>>() {})
                .returnResult();

        assertThat(result.getResponseBody()).containsOnlyKeys("traceparent");
        assertThat(result.getResponseBody().get("traceparent")).startsWith("00-" + CALLER_TRACE_ID + "-");
    }

    @Test
    void problemDetailsCarryTheTraceId() {
        // A Spring MVC error (GlobalExceptionHandler): an unknown endpoint.
        client.get().uri("/api/v1/no-such-endpoint")
                .exchange()
                .expectStatus().isNotFound()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .consumeWith(result -> {
                    String traceId = result.getResponseHeaders().getFirst(TraceIdFilter.HEADER);
                    assertThat(traceId).matches(TRACE_ID);
                    assertThat(new String(result.getResponseBodyContent())).contains("\"traceId\":\"" + traceId + "\"");
                });
    }

    @Test
    void asyncWorkOnTheApplicationTaskExecutorStaysInTheTrace() {
        EntityExchangeResult<String> result = client.get().uri("/api/v1/trace-probe/async")
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .returnResult();

        assertThat(result.getResponseBody()).isEqualTo(result.getResponseHeaders().getFirst(TraceIdFilter.HEADER));
    }

    private static String traceIdOf(RestTestClient.ResponseSpec response) {
        String traceId = response.returnResult().getResponseHeaders().getFirst(TraceIdFilter.HEADER);
        assertThat(traceId).as(TraceIdFilter.HEADER).isNotNull();
        return traceId;
    }

    // --- Test-only endpoints ---------------------------------------------------------------------

    @TestConfiguration
    static class ProbeConfig {
        @Bean
        TraceProbeController traceProbeController(Tracer tracer, Propagator propagator, TaskExecutor applicationTaskExecutor) {
            return new TraceProbeController(tracer, propagator, applicationTaskExecutor);
        }
    }

    @RestController
    @RequestMapping("/api/{version}/trace-probe")
    static class TraceProbeController {

        private static final Logger log = LoggerFactory.getLogger(TraceProbeController.class);

        private final Tracer tracer;
        private final Propagator propagator;
        private final TaskExecutor executor;

        TraceProbeController(Tracer tracer, Propagator propagator, TaskExecutor executor) {
            this.tracer = tracer;
            this.propagator = propagator;
            this.executor = executor;
        }

        @GetMapping(path = "/log", version = "1")
        void log() {
            log.info("trace probe handled");
        }

        @GetMapping(path = "/downstream-headers", version = "1")
        Map<String, String> downstreamHeaders() {
            Map<String, String> headers = new TreeMap<>();
            Span span = tracer.currentSpan();
            propagator.inject(span.context(), headers, Map::put);
            return headers;
        }

        /** The trace ID that log lines written by a task on the application executor carry. */
        @GetMapping(path = "/async", version = "1")
        String async() {
            CompletableFuture<String> traceId = new CompletableFuture<>();
            executor.execute(() -> traceId.complete(String.valueOf(MDC.get("traceId"))));
            return traceId.join();
        }
    }
}
