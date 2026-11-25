package com.shahabuddin.jobfinder.shared.api;

import com.shahabuddin.jobfinder.shared.Idempotent;
import com.shahabuddin.jobfinder.shared.PageQuery;
import com.shahabuddin.jobfinder.shared.ResourceNotFoundException;
import com.shahabuddin.jobfinder.shared.SortableBy;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.data.web.PagedModel;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.handler.HandlerExceptionResolverComposite;
import org.springframework.web.servlet.mvc.method.annotation.ExceptionHandlerExceptionResolver;
import org.springframework.web.method.ControllerAdviceBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The OpenAPI document describes the API as it really works (docs/adr/0016-openapi-documentation.md).
 * Each test names what springdoc-openapi documented before {@code OpenApiConfig} (measured), and some
 * send the request the document describes, so the document can't drift from the server's behavior.
 * Uses endpoints that exist only in this test.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "springdoc.api-docs.enabled=true", "springdoc.swagger-ui.enabled=true",
        // Generate the document per request: springdoc caches it, so mutation testing (which swaps
        // classes into a running context) saw none of its mutants (0 of 66 killed).
        "springdoc.cache.disabled=true"})
@AutoConfigureRestTestClient
class OpenApiTests {

    @Autowired
    private RestTestClient client;

    @Autowired
    private JsonMapper json;

    private JsonNode document;

    @BeforeEach
    void loadDocument() {
        document = json.readTree(client.get().uri("/v3/api-docs").exchange()
                .expectStatus().isOk()
                .expectBody(String.class).returnResult().getResponseBody());
    }

    @Test
    void pathsAreTheVersionedPathsClientsCall() {
        // Were "/api/1/doc-probe/jobs": springdoc substitutes the bare version for {version}.
        assertThat(fieldNames(document.path("paths")))
                .containsExactlyInAnyOrder("/api/v1/doc-probe/jobs", "/api/v1/doc-probe/jobs/{id}", "/api/v1/doc-probe/notes",
                        "/api/v1/doc-probe/companies", "/api/v1/doc-probe/companies/recent");
        // ...and the documented path is the one that works.
        client.get().uri("/api/v1/doc-probe/jobs/7").exchange().expectStatus().isNotFound();
    }

    @Test
    void onlyTheApiIsDocumented() {
        // The internal /error controller was documented, once per HTTP method (7 operations).
        assertThat(document.path("paths").has("/error")).isFalse();
        assertThat(document.path("servers").get(0).path("url").asString()).isEqualTo("/");
        assertThat(document.path("info").path("title").asString()).isEqualTo("Job Finder API");
    }

    @Test
    void aFilterRecordIsTheSeparateParametersClientsSend() {
        // Was one required query parameter "filter" with an object schema.
        JsonNode search = operation("/api/v1/doc-probe/jobs", "get");
        assertThat(parameterNames(search)).contains("q", "status", "minSalary").doesNotContain("filter");
        assertThat(parameter(search, "q").path("schema").path("minLength").asInt()).isEqualTo(2);
        assertThat(parameter(search, "q").path("required").asBoolean()).isFalse();
    }

    @Test
    void pagingIsDocumentedAsTheContractNotAsThePageQueryRecord() {
        // Was a "page" object parameter whose "sort" was {empty, sorted, unsorted}.
        JsonNode search = operation("/api/v1/doc-probe/jobs", "get");
        JsonNode size = parameter(search, "size").path("schema");
        assertThat(size.path("minimum").asInt()).isEqualTo(1);
        assertThat(size.path("maximum").asInt()).isEqualTo(PageQuery.MAX_SIZE);
        assertThat(size.path("default").asInt()).isEqualTo(PageQuery.DEFAULT_SIZE);
        assertThat(parameter(search, "page").path("schema").path("minimum").asInt()).isZero();

        JsonNode sort = parameter(search, "sort");
        assertThat(sort.path("explode").asBoolean()).isTrue();
        assertThat(texts(sort.path("schema").path("items").path("enum")))
                .containsExactly("title", "title,asc", "title,desc", "createdAt", "createdAt,asc", "createdAt,desc");
        assertThat(texts(sort.path("schema").path("default"))).containsExactly("createdAt,desc");
        assertThat(document.path("components").path("schemas").has("PageQuery")).isFalse();
        assertThat(document.path("components").path("schemas").has("SortObject")).isFalse();

        // No default order declared: no default documented. Not sortable at all: no sort parameter.
        JsonNode companies = operation("/api/v1/doc-probe/companies", "get");
        assertThat(parameter(companies, "sort").path("schema").has("default")).isFalse();
        assertThat(parameterNames(operation("/api/v1/doc-probe/companies/recent", "get"))).containsExactly("page", "size");

        // What it documents is accepted, as documented: repeated sort parameters, the largest size.
        client.get().uri("/api/v1/doc-probe/jobs?size=100&sort=title,desc&sort=createdAt").exchange().expectStatus().isOk();
    }

    @Test
    void idempotencyKeyIsRequiredWhereTheEndpointRequiresIt() {
        JsonNode create = operation("/api/v1/doc-probe/jobs", "post");
        JsonNode key = parameter(create, IdempotencyKey.HEADER);
        assertThat(key.path("in").asString()).isEqualTo("header");
        assertThat(key.path("required").asBoolean()).isTrue();
        assertThat(key.path("schema").path("minLength").asInt()).isEqualTo(IdempotencyKey.MIN_LENGTH);
        assertThat(key.path("schema").path("maxLength").asInt()).isEqualTo(IdempotencyKey.MAX_LENGTH);
        assertThat(responseRef(create, "409")).endsWith("/" + OpenApiConfig.REQUEST_IN_PROGRESS);
        assertThat(responseRef(create, "413")).endsWith("/" + OpenApiConfig.CONTENT_TOO_LARGE);
        assertThat(responseRef(create, "422")).endsWith("/" + OpenApiConfig.IDEMPOTENCY_KEY_REUSED);
        assertThat(create.path("responses").path("201").path("headers").has(IdempotencyFilter.REPLAYED_HEADER)).isTrue();
        // Only 2xx responses are replayed: not a business error the controller declares inline (found by
        // mutation testing: every inline response got the header).
        assertThat(create.path("responses").path("404").path("headers").has(IdempotencyFilter.REPLAYED_HEADER)).isFalse();

        // Optional on any other POST or PATCH (it is honoured whenever sent), absent on a GET.
        assertThat(parameter(operation("/api/v1/doc-probe/notes", "post"), IdempotencyKey.HEADER).path("required").asBoolean()).isFalse();
        assertThat(parameter(operation("/api/v1/doc-probe/jobs/{id}", "patch"), IdempotencyKey.HEADER).path("required").asBoolean()).isFalse();
        assertThat(parameterNames(operation("/api/v1/doc-probe/jobs/{id}", "get"))).doesNotContain(IdempotencyKey.HEADER);
    }

    @Test
    void everyOperationDocumentsTheErrorsTheRequestPipelineCanProduce() {
        // There were none: no 400, no 429, no application/problem+json anywhere.
        for (JsonNode operation : List.of(operation("/api/v1/doc-probe/jobs", "get"), operation("/api/v1/doc-probe/jobs", "post"),
                operation("/api/v1/doc-probe/jobs/{id}", "get"), operation("/api/v1/doc-probe/notes", "post"))) {
            assertThat(responseRef(operation, "400")).endsWith("/" + OpenApiConfig.BAD_REQUEST);
            assertThat(responseRef(operation, "429")).endsWith("/" + OpenApiConfig.TOO_MANY_REQUESTS);
            assertThat(responseRef(operation, "500")).endsWith("/" + OpenApiConfig.SERVER_ERROR);
            // A reference is only a reference: OpenAPI ignores anything next to "$ref".
            assertThat(fieldNames(operation.path("responses").path("400"))).containsExactly("$ref");
        }
        JsonNode badRequest = document.path("components").path("responses").path(OpenApiConfig.BAD_REQUEST);
        assertThat(badRequest.path("content").path(OpenApiConfig.PROBLEM_JSON).path("schema").path("$ref").asString())
                .endsWith("/" + OpenApiConfig.PROBLEM_SCHEMA);
        assertThat(badRequest.path("headers").has(TraceIdFilter.HEADER)).isTrue();
        assertThat(document.path("components").path("responses").path(OpenApiConfig.TOO_MANY_REQUESTS)
                .path("headers").has("Retry-After")).isTrue();
    }

    @Test
    void theProblemSchemaIsTheShapeTheApiSends() {
        // Spring's ProblemDetail schema had an invented "properties" member and no traceId or errors.
        JsonNode problem = document.path("components").path("schemas").path(OpenApiConfig.PROBLEM_SCHEMA).path("properties");
        assertThat(fieldNames(problem)).containsExactlyInAnyOrder("type", "title", "status", "detail", "instance", "traceId", "errors");
        JsonNode violation = document.path("components").path("schemas").path(OpenApiConfig.VIOLATION_SCHEMA).path("properties");
        assertThat(fieldNames(violation)).containsExactlyInAnyOrder("pointer", "parameter", "code", "detail");

        // A real error has exactly those members.
        JsonNode error = json.readTree(client.post().uri("/api/v1/doc-probe/notes").contentType(MediaType.APPLICATION_JSON)
                .body("{\"text\": \"\"}").exchange()
                .expectStatus().isBadRequest()
                .expectBody(String.class).returnResult().getResponseBody());
        assertThat(fieldNames(problem)).containsAll(fieldNames(error));
        assertThat(fieldNames(violation)).containsAll(fieldNames(error.path("errors").get(0)));
    }

    @Test
    void successStatusAndNotFoundComeFromTheController() {
        // springdoc can't see either: the status of a ResponseEntity is decided at runtime, and only
        // the use case knows when a resource is missing. Declared, they replace the default "200".
        JsonNode create = operation("/api/v1/doc-probe/jobs", "post");
        assertThat(create.path("responses").has("200")).isFalse();
        assertThat(create.path("responses").path("201").path("headers").has("Location")).isTrue();
        assertThat(responseRef(operation("/api/v1/doc-probe/jobs/{id}", "get"), "404")).endsWith("/" + OpenApiConfig.NOT_FOUND);
        assertThat(create.path("responses").path("201").path("headers").has(TraceIdFilter.HEADER)).isTrue();
    }

    @Test
    void requestBodiesRejectUnknownPropertiesResponsesStayOpen() {
        JsonNode schemas = document.path("components").path("schemas");
        assertThat(schemas.path("CreateJob").path("additionalProperties").asBoolean(true)).isFalse();
        assertThat(schemas.path("SalaryRange").path("additionalProperties").asBoolean(true)).as("nested in a request").isFalse();
        assertThat(schemas.path("Skill").path("additionalProperties").asBoolean(true)).as("in a list in a request").isFalse();
        assertThat(schemas.path("JobResponse").has("additionalProperties")).as("a new response property is not breaking").isFalse();

        // As documented: an unknown property is a 400.
        client.post().uri("/api/v1/doc-probe/notes").contentType(MediaType.APPLICATION_JSON)
                .body("{\"text\": \"hi\", \"featured\": true}").exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void swaggerUiIsServedAndCantBePointedAtAnotherDocument() {
        client.get().uri("/swagger-ui/index.html").exchange().expectStatus().isOk();
        // springdoc writes the option into the script the browser runs (verified: with the property
        // true, it contains queryConfigEnabled: "true"). Absent, Swagger UI ignores ?url= and ?configUrl=.
        String initializer = client.get().uri("/swagger-ui/swagger-initializer.js").exchange()
                .expectStatus().isOk().expectBody(String.class).returnResult().getResponseBody();
        assertThat(initializer).contains("\"configUrl\" : \"/v3/api-docs/swagger-config\"").doesNotContain("queryConfigEnabled");
    }

    @Test
    void ourExceptionHandlerComesBeforeTheAdviceSpringdocBrings(@Autowired HandlerExceptionResolverComposite resolver) {
        // springdoc registers a @RestControllerAdvice of its own while the docs are enabled. Both were
        // at the lowest precedence: which one Spring asked first depended on registration order.
        List<ControllerAdviceBean> advice = resolver.getExceptionResolvers().stream()
                .filter(ExceptionHandlerExceptionResolver.class::isInstance)
                .map(ExceptionHandlerExceptionResolver.class::cast)
                .findFirst().orElseThrow()
                .getExceptionHandlerAdviceCache().keySet().stream().toList();
        List<String> types = advice.stream().map(bean -> bean.getBeanType().getName()).toList();
        assertThat(types).anyMatch(type -> type.startsWith("org.springdoc."));
        assertThat(types.getFirst()).isEqualTo(GlobalExceptionHandler.class.getName());
        assertThat(advice.subList(1, advice.size())).allSatisfy(other ->
                assertThat(advice.getFirst().getOrder()).isLessThan(other.getOrder()));
    }

    // --- Helpers --------------------------------------------------------------------------------

    private JsonNode operation(String path, String method) {
        JsonNode operation = document.path("paths").path(path).path(method);
        assertThat(operation.isMissingNode()).as("%s %s is documented", method, path).isFalse();
        return operation;
    }

    private static JsonNode parameter(JsonNode operation, String name) {
        return StreamSupport.stream(operation.path("parameters").spliterator(), false)
                .filter(parameter -> parameter.path("name").asString().equals(name))
                .findFirst().orElseThrow(() -> new AssertionError("no parameter " + name + " in " + operation));
    }

    private static List<String> parameterNames(JsonNode operation) {
        return StreamSupport.stream(operation.path("parameters").spliterator(), false)
                .map(parameter -> parameter.path("name").asString()).toList();
    }

    private static String responseRef(JsonNode operation, String status) {
        return operation.path("responses").path(status).path("$ref").asString();
    }

    private static List<String> fieldNames(JsonNode node) {
        return node.propertyNames().stream().toList();
    }

    private static List<String> texts(JsonNode array) {
        return StreamSupport.stream(array.spliterator(), false).map(JsonNode::asString).toList();
    }

    // --- Test-only endpoints, written the way a module documents its controller -------------------

    enum Status { OPEN, CLOSED }

    record JobFilter(@Size(min = 2, max = 100) String q, List<Status> status, @PositiveOrZero Integer minSalary) {
    }

    record SalaryRange(@NotNull @PositiveOrZero Integer min, @NotNull Integer max) {
    }

    record Skill(@NotBlank String name) {
    }

    record CreateJob(@NotBlank @Size(max = 100) String title, @NotNull @Positive Integer openings,
                     @Valid SalaryRange salary, LocalDate closesOn, List<@Valid Skill> skills) {
    }

    record CompanyResponse(long id, String name) {
    }

    record JobResponse(long id, String title, Instant createdAt) {
    }

    record NoteRequest(@NotBlank String text) {
    }

    @TestConfiguration
    static class ProbeConfig {
        @Bean
        DocumentedJobController documentedJobController() {
            return new DocumentedJobController();
        }
    }

    @Tag(name = "Jobs", description = "Job postings")
    @RestController
    @RequestMapping("/api/{version}/doc-probe")
    static class DocumentedJobController {

        @Operation(summary = "Search jobs")
        @GetMapping(path = "/jobs", version = "1")
        PagedModel<JobResponse> search(@Valid JobFilter filter,
                                       @SortableBy(value = {"title", "createdAt"}, defaultSort = "createdAt,desc") PageQuery page) {
            return new PagedModel<>(new org.springframework.data.domain.PageImpl<>(List.of(), page.toPageable(), 0));
        }

        @Operation(summary = "Get a job")
        @ApiResponse(responseCode = "200", description = "The job")
        @ApiResponse(responseCode = "404", ref = OpenApiConfig.NOT_FOUND)
        @GetMapping(path = "/jobs/{id}", version = "1")
        JobResponse find(@PathVariable @Positive long id) {
            throw new ResourceNotFoundException("Job", id);
        }

        @Operation(summary = "Post a job")
        @ApiResponse(responseCode = "201", description = "Created",
                headers = @Header(name = "Location", description = "The new job's URL"))
        @ApiResponse(responseCode = "404", description = "The company of the job doesn't exist")
        @Idempotent
        @PostMapping(path = "/jobs", version = "1")
        ResponseEntity<JobResponse> create(@Valid @RequestBody CreateJob job) {
            return ResponseEntity.created(URI.create("/api/v1/doc-probe/jobs/1")).body(new JobResponse(1, job.title(), Instant.EPOCH));
        }

        @Operation(summary = "Rename a job")
        @PatchMapping(path = "/jobs/{id}", version = "1")
        JobResponse rename(@PathVariable @Positive long id, @Valid @RequestBody NoteRequest title) {
            return new JobResponse(id, title.text(), Instant.EPOCH);
        }

        @Operation(summary = "List companies")
        @GetMapping(path = "/companies", version = "1")
        PagedModel<CompanyResponse> companies(@SortableBy({"name"}) PageQuery page) {
            return new PagedModel<>(new org.springframework.data.domain.PageImpl<>(List.of(), page.toPageable(), 0));
        }

        @Operation(summary = "Recently added companies, newest first")
        @GetMapping(path = "/companies/recent", version = "1")
        PagedModel<CompanyResponse> recentCompanies(PageQuery page) {
            return new PagedModel<>(new org.springframework.data.domain.PageImpl<>(List.of(), page.toPageable(), 0));
        }

        @Operation(summary = "Add a note")
        @PostMapping(path = "/notes", version = "1")
        String note(@Valid @RequestBody NoteRequest note) {
            return "ok";
        }
    }
}
