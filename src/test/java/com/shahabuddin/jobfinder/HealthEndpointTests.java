package com.shahabuddin.jobfinder;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.client.RestTestClient;

/**
 * Starts the whole application on a random port and calls the health endpoints over real HTTP.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
class HealthEndpointTests {

    @Autowired
    private RestTestClient client;

    @Test
    void healthIsUp() {
        client.get().uri("/actuator/health")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").isEqualTo("UP");
    }

    @Test
    void livenessProbeIsUp() {
        client.get().uri("/actuator/health/liveness")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").isEqualTo("UP");
    }

    @Test
    void readinessProbeIsUp() {
        client.get().uri("/actuator/health/readiness")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").isEqualTo("UP");
    }

    @Test
    void healthDetailsAreHidden() {
        client.get().uri("/actuator/health")
                .exchange()
                .expectBody()
                .jsonPath("$.components").doesNotExist();
    }

    @Test
    void otherActuatorEndpointsAreNotExposed() {
        client.get().uri("/actuator/env")
                .exchange()
                .expectStatus().isNotFound();
    }
}
