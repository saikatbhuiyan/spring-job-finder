package com.shahabuddin.jobfinder.shared.api;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.json.JsonTest;
import org.springframework.boot.test.json.JacksonTester;
import tools.jackson.databind.exc.MismatchedInputException;
import tools.jackson.databind.exc.UnrecognizedPropertyException;

import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * A JSON <b>slice</b> test (docs/adr/0015-testing-strategy.md): {@code @JsonTest} starts only Jackson,
 * configured exactly as in the application, and {@link JacksonTester} reads and writes DTOs. The
 * fastest way to pin down a wire format: no HTTP, no controller.
 *
 * <p>Locks in the JSON rules of ADR 0005 (unknown properties rejected), ADR 0009 (no type coercion) and
 * ADR 0011 (ISO-8601 dates) for every DTO, since they're mapper-wide settings.
 */
@JsonTest
class JsonSliceTests {

    @Autowired
    private JacksonTester<JobResponse> response;

    @Autowired
    private JacksonTester<JobRequest> request;

    @Test
    void datesAreWrittenAsIso8601StringsNotNumbers() throws Exception {
        JobResponse job = new JobResponse(42, "Java developer", Instant.parse("2026-11-25T08:30:00Z"),
                LocalDate.parse("2026-12-31"), null);

        assertThat(response.write(job))
                .extractingJsonPathStringValue("$.createdAt").isEqualTo("2026-11-25T08:30:00Z");
        assertThat(response.write(job))
                .extractingJsonPathStringValue("$.closesOn").isEqualTo("2026-12-31");
    }

    @Test
    void nullPropertiesAreWritten() throws Exception {
        // An absent "salary" and "salary": null mean the same to most clients, but the documented
        // shape is the stable one: every property is always present.
        JobResponse job = new JobResponse(42, "Java developer", Instant.EPOCH, LocalDate.EPOCH, null);

        assertThat(response.write(job)).hasJsonPath("$.salary").extractingJsonPathValue("$.salary").isNull();
    }

    @Test
    void validRequestIsRead() throws Exception {
        assertThat(request.parseObject("""
                {"title": "Java developer", "openings": 2, "closesOn": "2026-12-31"}"""))
                .isEqualTo(new JobRequest("Java developer", 2, LocalDate.parse("2026-12-31")));
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', textBlock = """
            number for a string     | {"title": 123, "openings": 2}
            boolean for a string    | {"title": true, "openings": 2}
            string for a number     | {"title": "x", "openings": "2"}
            fraction for an integer | {"title": "x", "openings": 2.5}
            empty string for null   | {"title": "x", "openings": ""}
            number for a date       | {"title": "x", "openings": 2, "closesOn": 20261231}
            """)
    void valuesOfTheWrongTypeAreRejected(String name, String json) {
        assertThatExceptionOfType(MismatchedInputException.class).isThrownBy(() -> request.parseObject(json));
    }

    @Test
    void unknownPropertiesAreRejected() {
        assertThatExceptionOfType(UnrecognizedPropertyException.class).isThrownBy(() -> request.parseObject("""
                {"title": "x", "openings": 2, "featured": true}"""));
    }

    record JobRequest(String title, Integer openings, LocalDate closesOn) {
    }

    record JobResponse(long id, String title, Instant createdAt, LocalDate closesOn, Integer salary) {
    }
}
