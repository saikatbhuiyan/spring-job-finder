package com.shahabuddin.jobfinder.shared.api;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Locks in the request validation contract of docs/adr/0009-request-validation.md over real HTTP:
 * every violation has a machine-readable {@code code}, parameters and bodies fail in the same
 * {@code validation-error} format, JSON types are strict, and rejected values are never echoed.
 * Uses endpoints that exist only in this test.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
class ValidationTests {

    private static final String VALIDATION_ERROR =
            "https://github.com/saikatbhuiyan/spring-job-finder/blob/main/docs/problems.md#validation-error";

    @Autowired
    private RestTestClient client;

    // --- Bodies --------------------------------------------------------------------------------

    @Test
    void everyViolationIsReportedWithItsConstraintAsCode() {
        post("""
                {"title": "", "salary": {"min": 90000, "max": 50000}, "tags": [" ", "java"]}""")
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.type").isEqualTo(VALIDATION_ERROR)
                .jsonPath("$.errors.length()").isEqualTo(3)
                // A custom cross-field constraint reports on the field the client should fix.
                .jsonPath("$.errors[0].pointer").isEqualTo("#/salary/max")
                .jsonPath("$.errors[0].code").isEqualTo("ConsistentRange")
                .jsonPath("$.errors[0].detail").isEqualTo("must not be less than min")
                .jsonPath("$.errors[1].pointer").isEqualTo("#/tags/0")
                .jsonPath("$.errors[1].code").isEqualTo("NotBlank")
                .jsonPath("$.errors[2].pointer").isEqualTo("#/title")
                .jsonPath("$.errors[2].code").isEqualTo("NotBlank");
    }

    @Test
    void missingNumbersAreReportedTogetherWithTheOtherErrors() {
        // Wrapper types + @NotNull: Bean Validation sees every field. (A primitive int would fail
        // in Jackson first, alone, as "has an invalid value or type".)
        post("""
                {"title": "", "salary": {"max": 50000}}""")
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.errors.length()").isEqualTo(2)
                .jsonPath("$.errors[0].pointer").isEqualTo("#/salary/min")
                .jsonPath("$.errors[0].code").isEqualTo("NotNull")
                .jsonPath("$.errors[1].pointer").isEqualTo("#/title");
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', textBlock = """
            number for a string    | {"title": 123,  "salary": {"min": 1, "max": 2}}      | #/title
            boolean for a string   | {"title": true, "salary": {"min": 1, "max": 2}}      | #/title
            string for a number    | {"title": "x",  "salary": {"min": "1", "max": 2}}    | #/salary/min
            empty string for null  | {"title": "x",  "salary": {"min": "", "max": 2}}     | #/salary/min
            fraction for integer   | {"title": "x",  "salary": {"min": 1.5, "max": 2}}    | #/salary/min
            """)
    void valuesOfTheWrongJsonTypeAreRejectedNotConverted(String name, String json, String pointer) {
        // Jackson's defaults would accept all of these; 1.5 would silently become 1.
        post(json)
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.type").isEqualTo(VALIDATION_ERROR)
                .jsonPath("$.errors[0].pointer").isEqualTo(pointer)
                .jsonPath("$.errors[0].code").isEqualTo("InvalidType");
    }

    @Test
    void unknownPropertyHasItsOwnCode() {
        post("""
                {"title": "x", "salary": {"min": 1, "max": 2}, "featured": true}""")
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.errors[0].pointer").isEqualTo("#/featured")
                .jsonPath("$.errors[0].code").isEqualTo("UnknownProperty");
    }

    @Test
    void exceptionFromARequestConstructorIsAValidationErrorWithoutItsMessage() {
        // Was reported as "not valid JSON" (malformed-request-body), although the JSON was fine.
        client.post().uri("/api/v1/validation-probe/self-checking").contentType(MediaType.APPLICATION_JSON)
                .body("""
                        {"from": 10, "to": 5}""")
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.type").isEqualTo(VALIDATION_ERROR)
                .jsonPath("$.errors[0].pointer").isEqualTo("#")
                .jsonPath("$.errors[0].code").isEqualTo("InvalidValue")
                .consumeWith(result -> assertThat(new String(result.getResponseBodyContent())).doesNotContain("must not"));
    }

    // --- Parameters and headers ----------------------------------------------------------------

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', textBlock = """
            not a number         | /api/v1/validation-probe/search?page_size=xyz&q=java        | page_size | InvalidType
            unknown enum value   | /api/v1/validation-probe/search?status=OPENX&q=java         | status    | InvalidType
            out of range         | /api/v1/validation-probe/search?page_size=500&q=java        | page_size | Max
            missing              | /api/v1/validation-probe/search                             | q         | Required
            """)
    void invalidParametersAreValidationErrorsThatDontEchoTheValue(String name, String uri, String parameter, String code) {
        client.get().uri(uri).header("Idempotency-Key", "k")
                .exchange()
                .expectStatus().isBadRequest()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.type").isEqualTo(VALIDATION_ERROR)
                .jsonPath("$.errors.length()").isEqualTo(1)
                .jsonPath("$.errors[0].parameter").isEqualTo(parameter)
                .jsonPath("$.errors[0].code").isEqualTo(code)
                // Probe values have non-hex letters: every body carries a random hex traceId, which
                // contained "abc" now and then (seen: a flaky failure).
                .consumeWith(result -> assertThat(new String(result.getResponseBodyContent()))
                        .doesNotContain("xyz").doesNotContain("OPENX"));
    }

    @Test
    void queryParametersBoundToAnObjectAreReportedAsParameters() {
        // Were reported as body pointers ("#/minSalary").
        client.get().uri("/api/v1/validation-probe/filter?minSalary=-1&maxSalary=5000000")
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.type").isEqualTo(VALIDATION_ERROR)
                .jsonPath("$.errors.length()").isEqualTo(2)
                .jsonPath("$.errors[0].parameter").isEqualTo("maxSalary")
                .jsonPath("$.errors[0].code").isEqualTo("Max")
                .jsonPath("$.errors[0].pointer").doesNotExist()
                .jsonPath("$.errors[1].parameter").isEqualTo("minSalary")
                .jsonPath("$.errors[1].code").isEqualTo("PositiveOrZero");
    }

    @Test
    void unconvertibleQueryParameterOfAnObjectIsAnInvalidTypeWithoutSpringsMessage() {
        // Spring's own entry was code "typeMismatch" with a message naming java.lang.Integer and
        // echoing the input. The record can't be created then, so its constraints (maxSalary) are
        // only checked once every value converts.
        client.get().uri("/api/v1/validation-probe/filter?minSalary=xyz&maxSalary=5000000")
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.errors.length()").isEqualTo(1)
                .jsonPath("$.errors[0].parameter").isEqualTo("minSalary")
                .jsonPath("$.errors[0].code").isEqualTo("InvalidType")
                .jsonPath("$.errors[0].detail").isEqualTo("has an invalid value or type")
                // "xyz", not a hex-only value: the body's random traceId must not match it by chance.
                .consumeWith(result -> assertThat(new String(result.getResponseBodyContent()))
                        .doesNotContain("xyz").doesNotContain("java.lang"));
    }

    @Test
    void constraintOnAPathVariableIsReportedAsAParameter() {
        // Never exercised before (found by mutation testing): the commonest constraint in a REST API.
        client.get().uri("/api/v1/validation-probe/postings/0")
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.type").isEqualTo(VALIDATION_ERROR)
                .jsonPath("$.errors.length()").isEqualTo(1)
                .jsonPath("$.errors[0].parameter").isEqualTo("id")
                .jsonPath("$.errors[0].code").isEqualTo("Positive")
                .jsonPath("$.errors[0].detail").isEqualTo("must be greater than 0");
    }

    @Test
    void constraintOnAHeaderIsReportedWithTheHeaderName() {
        client.get().uri("/api/v1/validation-probe/client-version").header("X-Client-Version", "latest")
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.type").isEqualTo(VALIDATION_ERROR)
                .jsonPath("$.errors.length()").isEqualTo(1)
                .jsonPath("$.errors[0].parameter").isEqualTo("X-Client-Version")
                .jsonPath("$.errors[0].code").isEqualTo("Pattern")
                .consumeWith(result -> assertThat(new String(result.getResponseBodyContent())).doesNotContain("latest"));
    }

    @Test
    void bodyAndParameterErrorsTogetherKeepTheirOwnLocations() {
        // With a constraint on a parameter, Spring validates the whole method at once and reports the
        // body's errors through the same exception: they must still be body pointers.
        client.post().uri("/api/v1/validation-probe/postings/0/copies").contentType(MediaType.APPLICATION_JSON)
                .body("""
                        {"title": "", "salary": {"min": 1, "max": 2}}""")
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.type").isEqualTo(VALIDATION_ERROR)
                .jsonPath("$.errors.length()").isEqualTo(2)
                .jsonPath("$.errors[0].parameter").isEqualTo("id")
                .jsonPath("$.errors[0].code").isEqualTo("Positive")
                .jsonPath("$.errors[1].pointer").isEqualTo("#/title")
                .jsonPath("$.errors[1].code").isEqualTo("NotBlank");
    }

    /**
     * A constraint on the body's class reports at the object itself (a "global" error), the default for
     * class-level constraints. Never exercised before (found by mutation testing), by either path.
     */
    @ParameterizedTest(name = "{0}")
    @CsvSource({
            "body only (MethodArgumentNotValidException), /api/v1/validation-probe/periods",
            "body and a parameter constraint (HandlerMethodValidationException), /api/v1/validation-probe/periods/1"})
    void constraintOnTheWholeBodyIsReportedAtTheRoot(String name, String uri) {
        client.post().uri(uri).contentType(MediaType.APPLICATION_JSON)
                .body("""
                        {"from": "2026-01-01"}""")
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.type").isEqualTo(VALIDATION_ERROR)
                .jsonPath("$.errors.length()").isEqualTo(1)
                .jsonPath("$.errors[0].pointer").isEqualTo("#")
                .jsonPath("$.errors[0].code").isEqualTo("CompletePeriod")
                .jsonPath("$.errors[0].detail").isEqualTo("from and to must be given together");
    }

    @Test
    void missingRequiredHeaderIsAValidationError() {
        client.get().uri("/api/v1/validation-probe/search?q=java")
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.type").isEqualTo(VALIDATION_ERROR)
                .jsonPath("$.errors[0].parameter").isEqualTo("Idempotency-Key")
                .jsonPath("$.errors[0].code").isEqualTo("Required");
    }

    // --- Beyond the API boundary ---------------------------------------------------------------

    @Test
    void violationInsideTheApplicationLayerIsAServerError() {
        // Client input is validated at the API boundary. A constraint violated further in means our
        // own code passed invalid data along: a bug (500, logged), not the client's fault.
        client.post().uri("/api/v1/validation-probe/unchecked").contentType(MediaType.APPLICATION_JSON)
                .body("""
                        {"title": ""}""")
                .exchange()
                .expectStatus().isEqualTo(500)
                .expectBody()
                .jsonPath("$.detail").isEqualTo("An unexpected error occurred.");
    }

    private RestTestClient.ResponseSpec post(String json) {
        return client.post().uri("/api/v1/validation-probe/postings").contentType(MediaType.APPLICATION_JSON)
                .body(json)
                .exchange();
    }

    // --- Test-only endpoints, request types and constraint -------------------------------------

    record PostingRequest(@NotBlank @Size(max = 100) String title,
                          @NotNull @Valid SalaryRange salary,
                          @Size(max = 5) List<@NotBlank String> tags) {
    }

    @ConsistentRange
    record SalaryRange(@NotNull @PositiveOrZero Integer min, @NotNull Integer max) {
    }

    /** Checks its own arguments in the constructor: the anti-pattern the handler must still report well. */
    record SelfCheckingRange(int from, int to) {
        SelfCheckingRange {
            if (from > to) {
                throw new IllegalArgumentException("from must not exceed to");
            }
        }
    }

    record TitleRequest(String title) {
    }

    @CompletePeriod
    record PeriodRequest(LocalDate from, LocalDate to) {
    }

    /** A class-level constraint that reports at the object (no property node): a global error. */
    @Target(ElementType.TYPE)
    @Retention(RetentionPolicy.RUNTIME)
    @Constraint(validatedBy = CompletePeriodValidator.class)
    @interface CompletePeriod {
        String message() default "from and to must be given together";

        Class<?>[] groups() default {};

        Class<? extends Payload>[] payload() default {};
    }

    static class CompletePeriodValidator implements ConstraintValidator<CompletePeriod, PeriodRequest> {
        @Override
        public boolean isValid(PeriodRequest period, ConstraintValidatorContext context) {
            return (period.from() == null) == (period.to() == null);
        }
    }

    /** Search filters bound from query parameters (an implicit {@code @ModelAttribute}). */
    record SalaryFilter(@PositiveOrZero Integer minSalary, @Max(1_000_000) Integer maxSalary) {
    }

    enum Status { OPEN, CLOSED }

    /** Cross-field constraint: {@code max >= min}, reported on {@code max}. */
    @Target(ElementType.TYPE)
    @Retention(RetentionPolicy.RUNTIME)
    @Constraint(validatedBy = ConsistentRangeValidator.class)
    @interface ConsistentRange {
        String message() default "must not be less than min";

        Class<?>[] groups() default {};

        Class<? extends Payload>[] payload() default {};
    }

    static class ConsistentRangeValidator implements ConstraintValidator<ConsistentRange, SalaryRange> {
        @Override
        public boolean isValid(SalaryRange range, ConstraintValidatorContext context) {
            if (range == null || range.min() == null || range.max() == null || range.min() <= range.max()) {
                return true; // nulls are @NotNull's job
            }
            context.disableDefaultConstraintViolation();
            context.buildConstraintViolationWithTemplate(context.getDefaultConstraintMessageTemplate())
                    .addPropertyNode("max")
                    .addConstraintViolation();
            return false;
        }
    }

    @Validated
    static class PostingService {
        void publish(@NotBlank String title) {
        }
    }

    @TestConfiguration
    static class ProbeConfig {
        @Bean
        PostingService postingService() {
            return new PostingService();
        }

        @Bean
        ValidationProbeController validationProbeController(PostingService service) {
            return new ValidationProbeController(service);
        }
    }

    @RestController
    @RequestMapping("/api/{version}/validation-probe")
    static class ValidationProbeController {

        private final PostingService service;

        ValidationProbeController(PostingService service) {
            this.service = service;
        }

        @PostMapping(path = "/postings", version = "1")
        String create(@Valid @RequestBody PostingRequest request) {
            return "ok";
        }

        @PostMapping(path = "/self-checking", version = "1")
        String selfChecking(@Valid @RequestBody SelfCheckingRange request) {
            return "ok";
        }

        /** Deliberately not validated here, so the service-level constraint is what fails. */
        @PostMapping(path = "/unchecked", version = "1")
        String unchecked(@RequestBody TitleRequest request) {
            service.publish(request.title());
            return "ok";
        }

        @GetMapping(path = "/filter", version = "1")
        String filter(@Valid SalaryFilter filter) {
            return "ok";
        }

        @GetMapping(path = "/postings/{id}", version = "1")
        String posting(@PathVariable @Positive long id) {
            return "ok";
        }

        @GetMapping(path = "/client-version", version = "1")
        String clientVersion(@RequestHeader("X-Client-Version") @Pattern(regexp = "\\d+\\.\\d+") String version) {
            return "ok";
        }

        @PostMapping(path = "/periods", version = "1")
        String period(@Valid @RequestBody PeriodRequest request) {
            return "ok";
        }

        @PostMapping(path = "/periods/{id}", version = "1")
        String periodOf(@PathVariable @Positive long id, @Valid @RequestBody PeriodRequest request) {
            return "ok";
        }

        @PostMapping(path = "/postings/{id}/copies", version = "1")
        String copy(@PathVariable @Positive long id, @Valid @RequestBody PostingRequest request) {
            return "ok";
        }

        @GetMapping(path = "/search", version = "1")
        String search(@RequestParam(name = "q") String query,
                      @RequestParam(name = "page_size", defaultValue = "20") @Max(100) Integer pageSize,
                      @RequestParam(name = "status", required = false) Status status,
                      @RequestHeader("Idempotency-Key") String idempotencyKey) {
            return "ok";
        }
    }
}
