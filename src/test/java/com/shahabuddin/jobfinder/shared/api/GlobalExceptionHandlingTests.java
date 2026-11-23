package com.shahabuddin.jobfinder.shared.api;

import com.shahabuddin.jobfinder.shared.ConflictException;
import com.shahabuddin.jobfinder.shared.ResourceNotFoundException;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.webmvc.error.ErrorController;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.io.IOException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Locks in the error contract of docs/adr/0006-global-exception-handling.md over real HTTP: every
 * error is {@code application/problem+json}, validation errors name the failing field or
 * parameter, domain exceptions map to 404/409, and a 500 never reveals the exception message
 * (which is logged instead). Uses endpoints and a filter that exist only in this test.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        // Makes Spring's (otherwise silent) disconnected-client handling observable in one test.
        properties = {
                "logging.level.org.springframework.web.servlet.handler.DisconnectedClient=debug",
                "logging.level.org.springframework.web.servlet.mvc.method.annotation.DisconnectedClient=debug"})
@AutoConfigureRestTestClient
@ExtendWith(OutputCaptureExtension.class)
class GlobalExceptionHandlingTests {

    private static final String SECRET = "jdbc password=hunter2";
    private static final String DOCS = "https://github.com/saikatbhuiyan/spring-job-finder/blob/main/docs/problems.md#";

    @Autowired
    private RestTestClient client;

    @Autowired
    private ApplicationContext context;

    @Autowired
    private MeterRegistry meters;

    @Autowired
    private ErrorProbeController probe;

    @LocalServerPort
    private int port;

    @Test
    void exactlyOneExceptionHandlerAndErrorControllerAreActive() {
        // Ours replace Boot's ProblemDetailsExceptionHandler and BasicErrorController.
        assertThat(context.getBeansOfType(ResponseEntityExceptionHandler.class).values())
                .singleElement().isInstanceOf(GlobalExceptionHandler.class);
        assertThat(context.getBeansOfType(ErrorController.class).values())
                .singleElement().isInstanceOf(ProblemDetailsErrorController.class);
        // ...and it is the only advice, ours or a library's: a second one would be shadowed by
        // (or shadow) the Exception catch-all, depending on bean order.
        assertThat(context.getBeansWithAnnotation(ControllerAdvice.class).values())
                .singleElement().isInstanceOf(GlobalExceptionHandler.class);
    }

    // --- 500: unexpected exceptions ------------------------------------------------------------

    @Test
    void unexpectedExceptionIsA500ThatNeverLeaksTheMessage(CapturedOutput output) {
        client.get().uri("/api/v1/error-probe/boom")
                .exchange()
                .expectStatus().isEqualTo(500)
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.type").doesNotExist()
                .jsonPath("$.title").isEqualTo("Internal Server Error")
                .jsonPath("$.detail").isEqualTo("An unexpected error occurred.")
                .jsonPath("$.instance").isEqualTo("/api/v1/error-probe/boom")
                .consumeWith(result -> assertThat(new String(result.getResponseBodyContent())).doesNotContain("hunter2"));

        // ...but operators get the full story in the log, exactly once.
        assertThat(output).contains("GET /api/v1/error-probe/boom failed with 500")
                .contains("at com.shahabuddin.jobfinder.shared.api.GlobalExceptionHandlingTests$ErrorProbeController.boom");
        assertThat(occurrences(output.getAll(), "java.lang.IllegalStateException: " + SECRET)).isOne();
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({
            "response that can't be serialized, /api/v1/error-probe/unwritable,                  HttpMessageNotWritableException",
            "misnamed @PathVariable,            /api/v1/error-probe/misnamed-path-variable/7,    MissingPathVariableException"})
    void serverErrorsFromSpringsOwnHandlingAreLoggedAndGeneric(String name, String path, String exception, CapturedOutput output) {
        // Spring's inherited handlers answer these with a 500, but logged nothing and named
        // internals in the detail ("Required path variable 'jobId' is not present.").
        client.get().uri(path)
                .exchange()
                .expectStatus().isEqualTo(500)
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.detail").isEqualTo("An unexpected error occurred.")
                .jsonPath("$.instance").isEqualTo(path)
                .consumeWith(result -> assertThat(new String(result.getResponseBodyContent()))
                        .doesNotContain("hunter2").doesNotContain("jobId"));

        assertThat(output).contains("GET " + path + " failed with 500").contains(exception);
        assertThat(occurrences(output.getAll(), " ERROR ")).isOne();
    }

    @Test
    void failureAfterTheResponseWasCommittedIsLoggedAsAnError(CapturedOutput output) {
        // Serialization fails after the first buffer of a large body was sent: the 200 status is
        // already on the wire, so no error response is possible. It must not be a quiet WARN.
        client.get().uri("/api/v1/error-probe/fails-mid-stream")
                .exchange()
                .expectStatus().isOk();

        assertThat(output)
                .contains("GET /api/v1/error-probe/fails-mid-stream failed after the response was committed")
                .contains("java.lang.IllegalStateException: getter " + SECRET)
                .doesNotContain("Response already committed. Ignoring");
        assertThat(occurrences(output.getAll(), " ERROR ")).isOne();

        // Metrics can't show a 500 (the client did get a 200), but must not show a clean success.
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(meters.find("http.server.requests").tags(
                        "uri", "/api/{version}/error-probe/fails-mid-stream", "status", "200",
                        "exception", "HttpMessageNotWritableException").timer()).isNotNull());
    }

    @Test
    void clientThatDisconnectsWhileTheResponseIsWrittenIsNotAnError(CapturedOutput output) throws Exception {
        // Raw socket: a client that sends a request and goes away (closed tab, lost network).
        try (Socket socket = new Socket("localhost", port)) {
            socket.getOutputStream().write(
                    "GET /api/v1/error-probe/slow-large HTTP/1.1\r\nHost: localhost\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            assertThat(probe.slowLargeStarted.await(5, TimeUnit.SECONDS)).isTrue();
            socket.setSoLinger(true, 0); // close with a TCP reset
        }
        probe.clientGone.countDown();

        // Spring's own disconnected-client line (DEBUG, enabled for this test class)...
        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(output).contains("Looks like the client has gone away"));
        // ...and nothing that would page someone.
        assertThat(output.getAll()).doesNotContain(" ERROR ").doesNotContain(" WARN ");
    }

    @Test
    void serverErrorsAreRecordedInMetricsButExpectedOutcomesAreNot() {
        // Handled exceptions still reach http.server.requests (and traces) when the status is 5xx:
        // Spring records them after the @ExceptionHandler ran. A 404 is an expected outcome.
        client.get().uri("/api/v1/error-probe/boom").exchange().expectStatus().isEqualTo(500);
        client.get().uri("/api/v1/error-probe/jobs/42").exchange().expectStatus().isNotFound();

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            assertThat(meters.find("http.server.requests").tags(
                    "uri", "/api/{version}/error-probe/boom", "status", "500",
                    "outcome", "SERVER_ERROR", "exception", "IllegalStateException").timer()).isNotNull();
            assertThat(meters.find("http.server.requests").tags(
                    "uri", "/api/{version}/error-probe/jobs/{id}", "status", "404", "exception", "none").timer()).isNotNull();
        });
    }

    @Test
    void validationMessagesAreEnglishWhateverTheAcceptLanguage() {
        // By default Bean Validation follows Accept-Language: "darf nicht leer sein" inside an
        // otherwise English problem. The locale is fixed to English (application.properties).
        client.post().uri("/api/v1/error-probe/jobs")
                .header("Accept-Language", "de-DE")
                .contentType(MediaType.APPLICATION_JSON)
                .body("""
                        {"title": "", "salary": 1, "company": {"name": "Acme"}}""")
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.errors[0].pointer").isEqualTo("#/title")
                .jsonPath("$.errors[0].detail").isEqualTo("must not be blank");
    }

    // --- Domain exceptions ---------------------------------------------------------------------

    @Test
    void resourceNotFoundIsA404WithItsMessage() {
        client.get().uri("/api/v1/error-probe/jobs/42")
                .exchange()
                .expectStatus().isNotFound()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.type").isEqualTo(DOCS + "resource-not-found")
                .jsonPath("$.title").isEqualTo("Resource not found")
                .jsonPath("$.status").isEqualTo(404)
                .jsonPath("$.detail").isEqualTo("Job '42' was not found.")
                .jsonPath("$.instance").isEqualTo("/api/v1/error-probe/jobs/42");
    }

    @Test
    void conflictIsA409WithItsMessage() {
        client.post().uri("/api/v1/error-probe/jobs/42/close")
                .exchange()
                .expectStatus().isEqualTo(409)
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.type").isEqualTo(DOCS + "conflict")
                .jsonPath("$.title").isEqualTo("Conflict")
                .jsonPath("$.detail").isEqualTo("Job '42' is already closed.");
    }

    // --- 400: validation -----------------------------------------------------------------------

    @Test
    void invalidBodyListsEveryFailingFieldAsAJsonPointer() {
        client.post().uri("/api/v1/error-probe/jobs")
                .contentType(MediaType.APPLICATION_JSON)
                .body("""
                        {"title": " ", "salary": -5, "company": {"name": ""}, "tags": ["java", ""]}
                        """)
                .exchange()
                .expectStatus().isBadRequest()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.type").isEqualTo(DOCS + "validation-error")
                .jsonPath("$.title").isEqualTo("Invalid request")
                .jsonPath("$.detail").isEqualTo("The request contains invalid fields or parameters.")
                .jsonPath("$.instance").isEqualTo("/api/v1/error-probe/jobs")
                .jsonPath("$.errors.length()").isEqualTo(4)
                .jsonPath("$.errors[0].pointer").isEqualTo("#/company/name")
                .jsonPath("$.errors[0].detail").isEqualTo("must not be blank")
                .jsonPath("$.errors[1].pointer").isEqualTo("#/salary")
                .jsonPath("$.errors[1].detail").isEqualTo("must be greater than 0")
                .jsonPath("$.errors[2].pointer").isEqualTo("#/tags/1")
                .jsonPath("$.errors[3].pointer").isEqualTo("#/title")
                .jsonPath("$.errors[0].parameter").doesNotExist()
                .consumeWith(result -> assertThat(new String(result.getResponseBodyContent()))
                        .as("rejected values are not echoed back").doesNotContain("-5"));
    }

    @Test
    void invalidParameterIsReportedByTheNameTheClientUsed() {
        client.get().uri("/api/v1/error-probe/jobs?page_size=500")
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.type").isEqualTo(DOCS + "validation-error")
                .jsonPath("$.instance").isEqualTo("/api/v1/error-probe/jobs")
                .jsonPath("$.errors.length()").isEqualTo(1)
                .jsonPath("$.errors[0].parameter").isEqualTo("page_size")
                .jsonPath("$.errors[0].detail").isEqualTo("must be less than or equal to 100")
                .jsonPath("$.errors[0].pointer").doesNotExist();
    }

    @Test
    void unknownPropertyIsAValidationError() {
        client.post().uri("/api/v1/error-probe/jobs")
                .contentType(MediaType.APPLICATION_JSON)
                .body("""
                        {"title": "Java Developer", "salary": 90000, "company": {"name": "Acme"}, "featured": true}
                        """)
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.type").isEqualTo(DOCS + "validation-error")
                .jsonPath("$.errors[0].pointer").isEqualTo("#/featured")
                .jsonPath("$.errors[0].detail").isEqualTo("is not a known property");
    }

    @Test
    void wrongTypeIsAValidationErrorPointingAtTheNestedField() {
        client.post().uri("/api/v1/error-probe/jobs")
                .contentType(MediaType.APPLICATION_JSON)
                .body("""
                        {"title": "Java Developer", "salary": "lots", "company": {"name": "Acme"}}
                        """)
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.type").isEqualTo(DOCS + "validation-error")
                .jsonPath("$.errors[0].pointer").isEqualTo("#/salary")
                .jsonPath("$.errors[0].detail").isEqualTo("has an invalid value or type");
    }

    @Test
    void malformedJsonIsReportedWithoutParserInternals() {
        client.post().uri("/api/v1/error-probe/jobs")
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"title\": ")
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.type").isEqualTo(DOCS + "malformed-request-body")
                .jsonPath("$.title").isEqualTo("Malformed request body")
                .jsonPath("$.detail").isEqualTo("The request body is missing or is not valid JSON.")
                .consumeWith(result -> assertThat(new String(result.getResponseBodyContent()))
                        .doesNotContain("jackson").doesNotContain("com.shahabuddin"));
    }

    @Test
    void missingBodyIsMalformed() {
        client.post().uri("/api/v1/error-probe/jobs")
                .contentType(MediaType.APPLICATION_JSON)
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.type").isEqualTo(DOCS + "malformed-request-body");
    }

    // --- Spring MVC's own errors keep the standard mapping ---------------------------------------

    @Test
    void unsupportedMethodIsA405ProblemDetail() {
        client.delete().uri("/api/v1/error-probe/boom")
                .exchange()
                .expectStatus().isEqualTo(405)
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.title").isEqualTo("Method Not Allowed")
                .jsonPath("$.instance").isEqualTo("/api/v1/error-probe/boom");
    }

    // --- Errors outside Spring MVC (filters, container) ----------------------------------------

    @Test
    void exceptionInAFilterIsA500ProblemDetailWithoutTheMessage(CapturedOutput output) {
        client.get().uri("/api/v1/filter-probe/throws")
                .exchange()
                .expectStatus().isEqualTo(500)
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.title").isEqualTo("Internal Server Error")
                .jsonPath("$.detail").isEqualTo("An unexpected error occurred.")
                .jsonPath("$.instance").isEqualTo("/api/v1/filter-probe/throws")
                .jsonPath("$.timestamp").doesNotExist()
                .consumeWith(result -> assertThat(new String(result.getResponseBodyContent())).doesNotContain("hunter2"));

        // Logged once, by the servlet container, not a second time by the error controller.
        assertThat(occurrences(output.getAll(), "java.lang.IllegalStateException: filter " + SECRET)).isOne();
    }

    private static int occurrences(String text, String fragment) {
        return text.split(java.util.regex.Pattern.quote(fragment), -1).length - 1;
    }

    @Test
    void sendErrorFromAFilterIsAProblemDetail() {
        // What Spring Security's entry point does for an unauthenticated request.
        client.get().uri("/api/v1/filter-probe/unauthorized")
                .exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.status").isEqualTo(401)
                .jsonPath("$.title").isEqualTo("Unauthorized")
                .jsonPath("$.instance").isEqualTo("/api/v1/filter-probe/unauthorized");
    }

    @Test
    void requestingTheErrorPathDirectlyIsAClientError() {
        // Nobody should call /error directly. API versioning (version = 2nd path segment) rejects it
        // with a 400 before the controller runs, since "/error" has no version segment. Forwarded
        // errors aren't affected (see the filter tests above, incl. unversioned /actuator paths).
        client.get().uri("/error")
                .exchange()
                .expectStatus().is4xxClientError()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
    }

    // --- Test-only endpoints and filter ----------------------------------------------------------

    @TestConfiguration
    static class ProbeConfig {
        @Bean
        ErrorProbeController errorProbeController() {
            return new ErrorProbeController();
        }

        @Bean
        OncePerRequestFilter failingProbeFilter() {
            return new OncePerRequestFilter() {
                @Override
                protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                        throws ServletException, IOException {
                    switch (request.getRequestURI()) {
                        case "/api/v1/filter-probe/throws" -> throw new IllegalStateException("filter " + SECRET);
                        case "/api/v1/filter-probe/unauthorized" -> response.sendError(401);
                        default -> chain.doFilter(request, response);
                    }
                }
            };
        }
    }

    @RestController
    @RequestMapping("/api/{version}/error-probe")
    static class ErrorProbeController {

        final CountDownLatch slowLargeStarted = new CountDownLatch(1);
        final CountDownLatch clientGone = new CountDownLatch(1);

        @GetMapping(path = "/boom", version = "1")
        String boom() {
            throw new IllegalStateException(SECRET);
        }

        @GetMapping(path = "/unwritable", version = "1")
        Unwritable unwritable() {
            return new Unwritable();
        }

        @GetMapping(path = "/misnamed-path-variable/{id}", version = "1")
        String misnamedPathVariable(@PathVariable("jobId") String id) {
            return id;
        }

        /** Large enough to commit the response (several output buffers) before serialization fails. */
        @GetMapping(path = "/fails-mid-stream", version = "1")
        List<Object> failsMidStream() {
            List<Object> rows = new ArrayList<>(largeBody());
            rows.add(new Unwritable());
            return rows;
        }

        /** Waits until the test's client has disconnected, then writes a large body. */
        @GetMapping(path = "/slow-large", version = "1")
        List<String> slowLarge() throws InterruptedException {
            slowLargeStarted.countDown();
            clientGone.await(5, TimeUnit.SECONDS);
            return largeBody();
        }

        private static List<String> largeBody() {
            return IntStream.range(0, 100_000).mapToObj(i -> "row-" + i + "-padding-padding-padding").toList();
        }

        @GetMapping(path = "/jobs/{id}", version = "1")
        String job(@PathVariable long id) {
            throw new ResourceNotFoundException("Job", id);
        }

        @PostMapping(path = "/jobs/{id}/close", version = "1")
        void close(@PathVariable long id) {
            throw new ConflictException("Job '" + id + "' is already closed.");
        }

        @GetMapping(path = "/jobs", version = "1")
        List<String> search(@RequestParam(name = "page_size", defaultValue = "20") @Max(100) int pageSize) {
            return List.of();
        }

        @PostMapping(path = "/jobs", version = "1")
        ProbeJobRequest create(@Valid @RequestBody ProbeJobRequest request) {
            return request;
        }
    }

    record ProbeJobRequest(@NotBlank String title,
                           @Positive int salary,
                           @NotNull @Valid ProbeCompany company,
                           List<@NotBlank String> tags) {
    }

    record ProbeCompany(@NotBlank String name) {
    }

    /** A response Jackson can't write: the getter throws (a bug in our response code). */
    public static class Unwritable {
        public String getTitle() {
            throw new IllegalStateException("getter " + SECRET);
        }
    }
}
