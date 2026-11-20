package com.shahabuddin.jobfinder;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Locks in the API versioning contract: the version is the path segment after /api
 * (/api/v1/...), unsupported versions are rejected, and non-API endpoints such as
 * Actuator are not affected by version resolution.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
class ApiVersioningTests {

    @Autowired
    private RestTestClient client;

    @Test
    void resolvesVersionFromPath() {
        client.get().uri("/api/v1/versioning-probe")
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class).isEqualTo("v1");
    }

    @Test
    void rejectsUnsupportedVersionWithProblemDetail() {
        client.get().uri("/api/v99/versioning-probe")
                .exchange()
                .expectStatus().isBadRequest()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.detail").isEqualTo("Invalid API version: '99.0.0'.");
    }

    @Test
    void actuatorIsNotAffectedByVersioning() {
        client.get().uri("/actuator/health")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").isEqualTo("UP");
    }

    @TestConfiguration
    static class VersionedProbeConfig {
        @Bean
        VersioningProbeController versioningProbeController() {
            return new VersioningProbeController();
        }
    }

    /** Minimal versioned endpoint that exists only in this test. */
    @RestController
    @RequestMapping("/api/{version}/versioning-probe")
    static class VersioningProbeController {
        @GetMapping(version = "1")
        String v1() {
            return "v1";
        }
    }
}
