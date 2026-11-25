package com.shahabuddin.jobfinder.shared.api;

import com.shahabuddin.jobfinder.shared.PageQuery;
import com.shahabuddin.jobfinder.shared.SearchText;
import com.shahabuddin.jobfinder.shared.SortableBy;
import jakarta.validation.Valid;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.springframework.web.bind.annotation.BindParam;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Locks in the filtering contract of docs/adr/0011-filtering-and-search.md over real HTTP: unknown
 * query parameters are rejected, dates are ISO-8601 only, search text is normalized, and filters are
 * validated like any other input. Uses endpoints that exist only in this test.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
class FilteringTests {

    private static final String VALIDATION_ERROR =
            "https://github.com/saikatbhuiyan/spring-job-finder/blob/main/docs/problems.md#validation-error";

    @Autowired
    private RestTestClient client;

    @LocalServerPort
    private int port;

    // --- Unknown parameters ----------------------------------------------------------------------

    @Test
    void misspelledFilterIsRejectedInsteadOfSilentlyReturningEverything() {
        client.get().uri("/api/v1/filter-probe/jobs?titel=java")
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.type").isEqualTo(VALIDATION_ERROR)
                .jsonPath("$.errors.length()").isEqualTo(1)
                .jsonPath("$.errors[0].parameter").isEqualTo("titel")
                .jsonPath("$.errors[0].code").isEqualTo("UnknownParameter")
                .jsonPath("$.errors[0].detail").isEqualTo("is not a known parameter");
    }

    @Test
    void onlyTheUnknownParametersAreReportedInNameOrder() {
        client.get().uri("/api/v1/filter-probe/jobs?q=java&zeta=1&alpha=2&size=5")
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.errors[*].parameter").isEqualTo(List.of("alpha", "zeta"));
    }

    @Test
    void everyDeclaredFilterAndPagingParameterIsAccepted() {
        client.get().uri("/api/v1/filter-probe/jobs?q=java&minSalary=1000&location=DHAKA&location=REMOTE"
                        + "&status=OPEN&postedAfter=2026-01-15&postedSince=2026-01-15T10:00:00Z&page=0&size=5&sort=title")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.filter.location").isEqualTo(List.of("DHAKA", "REMOTE"))
                .jsonPath("$.filter.postedAfter").isEqualTo("2026-01-15")
                .jsonPath("$.filter.postedSince").isEqualTo("2026-01-15T10:00:00Z")
                .jsonPath("$.size").isEqualTo(5);
    }

    @Test
    void pagingParametersAreUnknownOnAnEndpointThatDoesntPage() {
        // A client that thinks the list is paged finds out, instead of getting page 0 forever.
        client.get().uri("/api/v1/filter-probe/named?page_size=5&page=2")
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.errors[0].parameter").isEqualTo("page");
        client.get().uri("/api/v1/filter-probe/named?page_size=5&q=java")
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void bindParamNamesAreTheKnownNames() {
        client.get().uri("/api/v1/filter-probe/bind?min_salary=5").exchange().expectStatus().isOk();
        client.get().uri("/api/v1/filter-probe/bind?minSalary=5")
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.errors[0].parameter").isEqualTo("minSalary");
    }

    @Test
    void anEndpointTakingAllParametersAsAMapAcceptsAnyName() {
        // The body proves the handler ran: an interceptor that stopped the request (preHandle returning
        // false) also answered 200, with an empty body (found by mutation testing).
        client.get().uri("/api/v1/filter-probe/raw?anything=1&else=2").exchange()
                .expectStatus().isOk()
                .expectBody(String.class).isEqualTo("anything,else");
    }

    /**
     * Query strings Tomcat can't parse were answered as a logged {@code 500} ("An unexpected error
     * occurred"): Tomcat throws when Spring MVC binds the first parameter (found while testing, ADR 0015).
     * Sent over a raw socket: Java's HTTP clients refuse to send most of these URIs at all.
     */
    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', textBlock = """
            a value without a name     | named?=x&q=java
            broken percent-encoding    | named?q=%zz
            a lone percent sign        | named?q=%
            percent-encoded non-UTF-8  | named?q=%E9
            """)
    void unparseableQueryStringsAre400(String name, String pathAndQuery) throws Exception {
        String response = rawGet("/api/v1/filter-probe/" + pathAndQuery);

        assertThat(response).startsWith("HTTP/1.1 400 ")
                .contains("\"detail\":\"The request's parameters could not be parsed.\"")
                .contains("\"traceId\":")
                // Tomcat's own message ("Invalid chunk ... with a value of [=x] ignored") quotes the raw input.
                .doesNotContain("Invalid chunk");
    }

    @Test
    void tooManyParametersIs400() throws Exception {
        // Tomcat's limit (server.tomcat.max-parameter-count, 1000) protects against hash-flooding.
        String parameters = IntStream.range(0, 1001).mapToObj(i -> "p" + i + "=1").collect(Collectors.joining("&"));

        assertThat(rawGet("/api/v1/filter-probe/raw?" + parameters)).startsWith("HTTP/1.1 400 ");
    }

    @Test
    void actuatorIsNotAffected() {
        client.get().uri("/actuator/health?unknown=1").exchange().expectStatus().isOk();
    }

    // --- Dates -------------------------------------------------------------------------------------

    @ParameterizedTest(name = "{0}={1}")
    @CsvSource({
            "postedAfter, 01/02/26",           // was read as 2 January
            "postedAfter, 1/15/26",
            "postedAfter, 2026-1-5",
            "postedSince, 2026-01-15T10:00:00"  // no offset: which time zone?
    })
    void datesThatAreNotIso8601AreRejected(String parameter, String value) {
        client.get().uri("/api/v1/filter-probe/jobs?" + parameter + "=" + value)
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.errors[0].parameter").isEqualTo(parameter)
                .jsonPath("$.errors[0].code").isEqualTo("InvalidType");
    }

    // --- Search text ---------------------------------------------------------------------------

    @Test
    void searchTextIsNormalized() {
        client.get().uri("/api/v1/filter-probe/jobs?q=  java   developer ")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.filter.q").isEqualTo("java developer");
    }

    @Test
    void blankSearchTextMeansNoSearch() {
        // What an empty search box submits.
        client.get().uri("/api/v1/filter-probe/jobs?q=   ")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.filter.q").doesNotExist();
    }

    @Test
    void tooShortSearchTextIsAValidationError() {
        client.get().uri("/api/v1/filter-probe/jobs?q= a ")
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.errors[0].parameter").isEqualTo("q")
                .jsonPath("$.errors[0].code").isEqualTo("Size");
    }

    // --- Lists ------------------------------------------------------------------------------------

    @Test
    void commasSplitListValuesSoListFiltersTakeCodes() {
        // Spring splits a list parameter on commas: "Dhaka, Bangladesh" would be two values. List
        // filters therefore take codes (DHAKA), never free text.
        client.get().uri("/api/v1/filter-probe/jobs?location=DHAKA,REMOTE")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.filter.location").isEqualTo(List.of("DHAKA", "REMOTE"));
    }

    /** One HTTP/1.1 request, sent byte for byte as given: the status line, headers and body. */
    private String rawGet(String pathAndQuery) throws IOException {
        try (Socket socket = new Socket("localhost", port)) {
            socket.getOutputStream().write(("GET " + pathAndQuery + " HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n")
                    .getBytes(StandardCharsets.ISO_8859_1));
            return new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    // --- Test-only endpoints -------------------------------------------------------------------

    enum Status { OPEN, CLOSED }

    /** A typical search filter: every property optional, validated, search text normalized. */
    record JobFilter(@Size(min = 2, max = 100) String q,
                     @PositiveOrZero Integer minSalary,
                     @Size(max = 10) List<String> location,
                     Status status,
                     LocalDate postedAfter,
                     Instant postedSince) {
        JobFilter {
            q = SearchText.normalize(q);
        }
    }

    record SearchResponse(JobFilter filter, int page, int size) {
    }

    record SnakeCaseFilter(@BindParam("min_salary") Integer minSalary) {
    }

    @TestConfiguration
    static class ProbeConfig {
        @Bean
        FilterProbeController filterProbeController() {
            return new FilterProbeController();
        }
    }

    @RestController
    @RequestMapping("/api/{version}/filter-probe")
    static class FilterProbeController {

        @GetMapping(path = "/jobs", version = "1")
        SearchResponse jobs(@Valid JobFilter filter, @SortableBy({"title", "createdAt"}) PageQuery page) {
            return new SearchResponse(filter, page.page(), page.size());
        }

        @GetMapping(path = "/named", version = "1")
        String named(@RequestParam(name = "page_size", defaultValue = "20") Integer pageSize, String q) {
            return "ok";
        }

        @GetMapping(path = "/bind", version = "1")
        String bind(@Valid SnakeCaseFilter filter) {
            return "ok";
        }

        @GetMapping(path = "/raw", version = "1")
        String raw(@RequestParam Map<String, String> parameters) {
            return String.join(",", new TreeSet<>(parameters.keySet()));
        }
    }
}
