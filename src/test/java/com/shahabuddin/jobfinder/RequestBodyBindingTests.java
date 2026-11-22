package com.shahabuddin.jobfinder;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Locks in the request DTO binding contract (docs/adr/0005-dtos-at-the-api-boundary.md):
 * records bind without annotations, and properties the DTO doesn't declare are rejected
 * with 400 Problem Details instead of being silently ignored.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
class RequestBodyBindingTests {

    @Autowired
    private RestTestClient client;

    @Test
    void bindsJsonOntoARecord() {
        client.post().uri("/api/v1/binding-probe")
                .contentType(MediaType.APPLICATION_JSON)
                .body("""
                        {"title": "Java Developer", "salary": 90000}
                        """)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.title").isEqualTo("Java Developer")
                .jsonPath("$.salary").isEqualTo(90000);
    }

    @Test
    void rejectsPropertiesTheDtoDoesNotDeclare() {
        // "featured" isn't part of the DTO: a client must not be able to send fields we don't accept
        client.post().uri("/api/v1/binding-probe")
                .contentType(MediaType.APPLICATION_JSON)
                .body("""
                        {"title": "Java Developer", "salary": 90000, "featured": true}
                        """)
                .exchange()
                .expectStatus().isBadRequest()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.status").isEqualTo(400);
    }

    @TestConfiguration
    static class BindingProbeConfig {
        @Bean
        BindingProbeController bindingProbeController() {
            return new BindingProbeController();
        }
    }

    /** Minimal endpoint that exists only in this test; echoes the bound DTO. */
    @RestController
    @RequestMapping("/api/{version}/binding-probe")
    static class BindingProbeController {
        @PostMapping(version = "1")
        ProbeRequest echo(@RequestBody ProbeRequest request) {
            return request;
        }
    }

    record ProbeRequest(String title, int salary) {
    }
}
