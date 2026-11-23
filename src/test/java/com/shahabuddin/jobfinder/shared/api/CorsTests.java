package com.shahabuddin.jobfinder.shared.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.URI;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Locks in the CORS policy of docs/adr/0007-cors-policy.md over real HTTP, with the headers a
 * browser would send. (CORS is enforced by browsers; the server's part is these headers, and
 * rejecting cross-origin requests from origins that aren't allowed.)
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "jobfinder.cors.allowed-origins=https://app.example.com")
@AutoConfigureRestTestClient
class CorsTests {

    private static final String FRONTEND = "https://app.example.com";
    private static final String EVIL = "https://evil.example";

    @Autowired
    private RestTestClient client;

    @Autowired
    private CorsProbeController probe;

    @LocalServerPort
    private int port;

    @BeforeEach
    void resetCounter() {
        probe.posts.set(0);
    }

    @Test
    void preflightFromTheFrontendIsAllowedAndCached() {
        client.options().uri("/api/v1/cors-probe")
                .header("Origin", FRONTEND)
                .header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "content-type, authorization")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("Access-Control-Allow-Origin", FRONTEND)
                .expectHeader().valueEquals("Access-Control-Allow-Methods", "GET,POST,PUT,PATCH,DELETE")
                .expectHeader().valueEquals("Access-Control-Allow-Headers", "content-type, authorization")
                .expectHeader().valueEquals("Access-Control-Max-Age", "3600")
                .expectHeader().doesNotExist("Access-Control-Allow-Credentials");
    }

    @Test
    void preflightForAMethodTheApiDoesNotUseIsRejected() {
        client.options().uri("/api/v1/cors-probe")
                .header("Origin", FRONTEND)
                .header("Access-Control-Request-Method", "TRACE")
                .exchange()
                .expectStatus().isForbidden()
                .expectHeader().doesNotExist("Access-Control-Allow-Origin");
    }

    @Test
    void preflightForAHeaderThatIsNotAllowedIsRejected() {
        client.options().uri("/api/v1/cors-probe")
                .header("Origin", FRONTEND)
                .header("Access-Control-Request-Method", "GET")
                .header("Access-Control-Request-Headers", "x-internal-debug")
                .exchange()
                .expectStatus().isForbidden();
    }

    @Test
    void responseToTheFrontendExposesTheHeadersItNeeds() {
        client.post().uri("/api/v1/cors-probe")
                .header("Origin", FRONTEND)
                .exchange()
                .expectStatus().isCreated()
                .expectHeader().valueEquals("Access-Control-Allow-Origin", FRONTEND)
                .expectHeader().valueEquals("Access-Control-Expose-Headers", "Location, Deprecation, Sunset, Link")
                .expectHeader().exists("Location")
                .expectHeader().values("Vary", vary -> assertThat(vary).contains("Origin"));
        assertThat(probe.posts).hasValue(1);
    }

    @Test
    void requestFromAnotherOriginIsRejectedBeforeTheHandlerRuns() {
        // A "simple" cross-origin POST is sent by browsers without a preflight; rejecting it here
        // means it never executes (the browser hiding the response alone wouldn't prevent that).
        client.post().uri("/api/v1/cors-probe")
                .header("Origin", EVIL)
                .exchange()
                .expectStatus().isForbidden()
                .expectHeader().doesNotExist("Access-Control-Allow-Origin");
        assertThat(probe.posts).hasValue(0);
    }

    @Test
    void sameOriginAndNonBrowserClientsAreNotAffected() {
        // CORS is not access control: clients that don't send Origin (curl, other servers) and
        // same-origin pages are served normally. Protect data with authentication, not CORS.
        client.get().uri("/api/v1/cors-probe")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().doesNotExist("Access-Control-Allow-Origin");
        client.get().uri("/api/v1/cors-probe")
                .header("Origin", "http://localhost:" + port)
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void errorResponsesCarryCorsHeadersSoTheFrontendCanReadThem() {
        // Without CORS headers the browser hides the Problem Details and reports a "CORS error".
        client.get().uri("/api/v1/cors-probe/boom").header("Origin", FRONTEND)
                .exchange()
                .expectStatus().isEqualTo(500)
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectHeader().valueEquals("Access-Control-Allow-Origin", FRONTEND);
        client.get().uri("/api/v1/does-not-exist").header("Origin", FRONTEND)
                .exchange()
                .expectStatus().isNotFound()
                .expectHeader().valueEquals("Access-Control-Allow-Origin", FRONTEND);
    }

    @Test
    void rejectionsByAFilterCarryCorsHeaders() {
        // What Spring Security will do for an unauthenticated request: sendError(401) in a filter.
        client.get().uri("/api/v1/cors-probe/unauthorized").header("Origin", FRONTEND)
                .exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectHeader().valueEquals("Access-Control-Allow-Origin", FRONTEND);
    }

    @Test
    void actuatorIsNotCorsEnabled() {
        client.get().uri("/actuator/health").header("Origin", FRONTEND)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().doesNotExist("Access-Control-Allow-Origin");
    }

    @TestConfiguration
    static class ProbeConfig {
        @Bean
        CorsProbeController corsProbeController() {
            return new CorsProbeController();
        }

        @Bean
        OncePerRequestFilter unauthorizedProbeFilter() {
            return new OncePerRequestFilter() {
                @Override
                protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                        throws ServletException, IOException {
                    if (request.getRequestURI().equals("/api/v1/cors-probe/unauthorized")) {
                        response.sendError(401);
                        return;
                    }
                    chain.doFilter(request, response);
                }
            };
        }
    }

    /** Endpoints that exist only in this test. */
    @RestController
    @RequestMapping("/api/{version}/cors-probe")
    static class CorsProbeController {

        final AtomicInteger posts = new AtomicInteger();

        @GetMapping(version = "1")
        String get() {
            return "ok";
        }

        @PostMapping(version = "1")
        ResponseEntity<Void> create() {
            posts.incrementAndGet();
            return ResponseEntity.created(URI.create("/api/v1/cors-probe/1")).build();
        }

        @GetMapping(path = "/boom", version = "1")
        String boom() {
            throw new IllegalStateException("boom");
        }
    }
}
