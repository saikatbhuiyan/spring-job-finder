package com.shahabuddin.jobfinder.shared.api;

import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Locks in the rate limit of docs/adr/0012-rate-limiting.md over real HTTP, with a small limit:
 * 3 requests per minute (one token every 20 s). Every test request comes from 127.0.0.1, so each
 * test gets a fresh context and therefore fresh buckets.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "jobfinder.rate-limit.requests=3",
        "jobfinder.rate-limit.period=1m",
        "jobfinder.cors.allowed-origins=https://app.example.com"})
@AutoConfigureRestTestClient
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class RateLimitTests {

    @Autowired
    private RestTestClient client;

    @Autowired
    private RateProbeController probe;

    @Autowired
    private MeterRegistry meters;

    @Test
    void requestsOverTheLimitAre429WithRetryAfterAndNeverReachTheController() {
        for (int i = 0; i < 3; i++) {
            client.get().uri("/api/v1/rate-probe").exchange().expectStatus().isOk();
        }
        client.get().uri("/api/v1/rate-probe")
                .exchange()
                .expectStatus().isEqualTo(429)
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectHeader().value("Retry-After", value -> assertThat(Integer.parseInt(value)).isBetween(1, 20))
                .expectBody()
                .jsonPath("$.status").isEqualTo(429)
                .jsonPath("$.title").isEqualTo("Too Many Requests")
                .jsonPath("$.detail").value(detail -> assertThat((String) detail).startsWith("Too many requests. Retry after "))
                .jsonPath("$.instance").isEqualTo("/api/v1/rate-probe")
                // A rejected request is still traced (ADR 0013): the trace ID filter runs before this one.
                .consumeWith(result -> {
                    String traceId = result.getResponseHeaders().getFirst("X-Trace-Id");
                    assertThat(traceId).matches("[0-9a-f]{32}");
                    assertThat(new String(result.getResponseBodyContent())).contains("\"traceId\":\"" + traceId + "\"");
                });
        assertThat(probe.calls).hasValue(3);
    }

    @Test
    void healthProbesAreNeverLimited() {
        // A throttled liveness probe would make the platform restart a healthy instance.
        exhaust();
        for (int i = 0; i < 10; i++) {
            client.get().uri("/actuator/health").exchange().expectStatus().isOk();
        }
    }

    @Test
    void aForgedForwardedForHeaderDoesNotMakeANewClient() {
        // Spring/Tomcat ignore X-Forwarded-For unless configured to trust a proxy, so rotating it
        // can't buy fresh buckets.
        for (String forged : new String[]{"203.0.113.1", "203.0.113.2", "203.0.113.3"}) {
            client.get().uri("/api/v1/rate-probe").header("X-Forwarded-For", forged).exchange().expectStatus().isOk();
        }
        client.get().uri("/api/v1/rate-probe").header("X-Forwarded-For", "203.0.113.4")
                .exchange()
                .expectStatus().isEqualTo(429);
    }

    @Test
    void aBrowserCanReadTheRejectionAndItsRetryAfter() {
        exhaust();
        client.get().uri("/api/v1/rate-probe").header("Origin", "https://app.example.com")
                .exchange()
                .expectStatus().isEqualTo(429)
                .expectHeader().valueEquals("Access-Control-Allow-Origin", "https://app.example.com")
                .expectHeader().value("Access-Control-Expose-Headers", value -> assertThat(value).contains("Retry-After"));
    }

    @Test
    void rejectionsAreRecordedInMetrics() {
        exhaust();
        client.get().uri("/api/v1/rate-probe").exchange().expectStatus().isEqualTo(429);
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(meters.find("http.server.requests").tag("status", "429").timer()).isNotNull());
    }

    private void exhaust() {
        for (int i = 0; i < 3; i++) {
            client.get().uri("/api/v1/rate-probe").exchange().expectStatus().isOk();
        }
    }

    @TestConfiguration
    static class ProbeConfig {
        @Bean
        RateProbeController rateProbeController() {
            return new RateProbeController();
        }
    }

    @RestController
    @RequestMapping("/api/{version}/rate-probe")
    static class RateProbeController {
        final AtomicInteger calls = new AtomicInteger();

        @GetMapping(version = "1")
        String get() {
            calls.incrementAndGet();
            return "ok";
        }
    }
}
