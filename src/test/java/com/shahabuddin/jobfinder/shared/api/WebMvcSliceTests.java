package com.shahabuddin.jobfinder.shared.api;

import com.shahabuddin.jobfinder.shared.Idempotent;
import com.shahabuddin.jobfinder.shared.ResourceNotFoundException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

/**
 * A controller <b>slice</b> test, the kind every module writes for its controllers
 * (docs/adr/0015-testing-strategy.md): {@code @WebMvcTest} starts only the web layer (no server, no
 * filters' dependencies, no use cases), {@link MockMvcTester} sends requests through Spring MVC in
 * memory, and the use case is a Mockito mock ({@link MockitoBean}).
 *
 * <p>Also locks in that a slice gets the <b>same API contract as the application</b>: Problem Details,
 * validation codes, strict JSON types, unknown query parameters and the {@code Idempotency-Key}
 * requirement. Before ADR 0015 no slice could start ({@code IdempotencyConfig} pulled the filter and its
 * {@code MeterRegistry} into every slice), and once it could, {@code {"title": 123}} was accepted, which
 * the application rejects.
 */
@WebMvcTest(WebMvcSliceTests.PostingController.class)
@Import(WebMvcSliceTests.PostingController.class)
class WebMvcSliceTests {

    private static final String KEY = "8e03978e-40d5-43e8-bc93-6894a57f9324";

    @Autowired
    private MockMvcTester mvc;

    @MockitoBean
    private Postings postings;

    @Test
    void createReturns201WithLocationAndCallsTheUseCaseOnce() {
        given(postings.publish("Java developer", 2)).willReturn(new Posting(42, "Java developer", 2));

        assertThat(mvc.post().uri("/api/v1/slice-probe/postings").header(IdempotencyKey.HEADER, KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"title": "Java developer", "openings": 2}"""))
                .hasStatus(HttpStatus.CREATED)
                .hasHeader("Location", "/api/v1/slice-probe/postings/42")
                .bodyJson().isLenientlyEqualTo("""
                        {"id": 42, "title": "Java developer", "openings": 2}""");

        then(postings).should().publish("Java developer", 2);
        then(postings).shouldHaveNoMoreInteractions();
    }

    @Test
    void invalidRequestNeverReachesTheUseCase() {
        // Only a mock can prove this: the response alone doesn't show what the use case was asked to do.
        assertThat(postWithKey("""
                {"title": " ", "openings": 0}"""))
                .hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson()
                .hasPathSatisfying("$.type", type -> type.assertThat().isEqualTo(ProblemTypes.VALIDATION_ERROR.toString()))
                .hasPathSatisfying("$.errors[0].pointer", p -> p.assertThat().isEqualTo("#/openings"))
                .hasPathSatisfying("$.errors[0].code", c -> c.assertThat().isEqualTo("Positive"))
                .hasPathSatisfying("$.errors[1].pointer", p -> p.assertThat().isEqualTo("#/title"))
                .hasPathSatisfying("$.errors[1].code", c -> c.assertThat().isEqualTo("NotBlank"));

        then(postings).shouldHaveNoInteractions();
    }

    @Test
    void jsonTypesAreAsStrictAsInTheApplication() {
        // Guards src/test/resources/META-INF/spring/...AutoConfigureJson.imports: without it a slice
        // accepted 123 as the string "123" (JsonBindingConfig isn't component-scanned in slices).
        assertThat(postWithKey("""
                {"title": 123, "openings": 2}"""))
                .hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson()
                .hasPathSatisfying("$.errors[0].pointer", p -> p.assertThat().isEqualTo("#/title"))
                .hasPathSatisfying("$.errors[0].code", c -> c.assertThat().isEqualTo("InvalidType"));

        then(postings).shouldHaveNoInteractions();
    }

    @Test
    void missingIdempotencyKeyOnAnIdempotentEndpointIs400() {
        assertThat(mvc.post().uri("/api/v1/slice-probe/postings").contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"title": "Java developer", "openings": 2}"""))
                .hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson()
                .hasPathSatisfying("$.errors[0].parameter", p -> p.assertThat().isEqualTo(IdempotencyKey.HEADER))
                .hasPathSatisfying("$.errors[0].code", c -> c.assertThat().isEqualTo("Required"));

        then(postings).shouldHaveNoInteractions();
    }

    @Test
    void unknownQueryParameterIs400() {
        assertThat(mvc.get().uri("/api/v1/slice-probe/postings/42?expand=company"))
                .hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson()
                .hasPathSatisfying("$.errors[0].parameter", p -> p.assertThat().isEqualTo("expand"))
                .hasPathSatisfying("$.errors[0].code", c -> c.assertThat().isEqualTo("UnknownParameter"));
    }

    @Test
    void domainExceptionFromTheUseCaseBecomesProblemDetails() {
        given(postings.find(7)).willThrow(new ResourceNotFoundException("Posting", 7));

        assertThat(mvc.get().uri("/api/v1/slice-probe/postings/7"))
                .hasStatus(HttpStatus.NOT_FOUND)
                .hasContentType(MediaType.APPLICATION_PROBLEM_JSON)
                .bodyJson().isLenientlyEqualTo("""
                        {"type": "%s", "status": 404, "detail": "Posting '7' was not found.",
                         "instance": "/api/v1/slice-probe/postings/7"}""".formatted(ProblemTypes.RESOURCE_NOT_FOUND));
    }

    @Test
    void onlyTheWebLayerIsStarted(@Autowired ApplicationContext context) {
        // What makes a slice fast: servlet filters' infrastructure and use cases aren't there.
        assertThat(context.getBeanNamesForType(IdempotencyStore.class)).isEmpty();
        assertThat(context.getBeanNamesForType(RateLimitProperties.class)).isEmpty();
        assertThat(context.getBeanNamesForType(GlobalExceptionHandler.class)).hasSize(1);
    }

    private MockMvcTester.MockMvcRequestBuilder postWithKey(String json) {
        return mvc.post().uri("/api/v1/slice-probe/postings").header(IdempotencyKey.HEADER, KEY)
                .contentType(MediaType.APPLICATION_JSON).content(json);
    }

    // --- The code under test: a controller as a module would write it, with its use case ---------

    record Posting(long id, String title, int openings) {
    }

    /** The application layer's use case, which the slice replaces with a mock. */
    interface Postings {
        Posting publish(String title, int openings);

        Posting find(long id);
    }

    record CreatePostingRequest(@NotBlank String title, @NotNull @Positive Integer openings) {
    }

    record PostingResponse(long id, String title, int openings) {
        static PostingResponse of(Posting posting) {
            return new PostingResponse(posting.id(), posting.title(), posting.openings());
        }
    }

    @RestController
    @RequestMapping("/api/{version}/slice-probe/postings")
    static class PostingController {

        private final Postings postings;

        PostingController(Postings postings) {
            this.postings = postings;
        }

        @Idempotent
        @PostMapping(version = "1")
        ResponseEntity<PostingResponse> create(@Valid @RequestBody CreatePostingRequest request) {
            Posting posting = postings.publish(request.title(), request.openings());
            return ResponseEntity.created(URI.create("/api/v1/slice-probe/postings/" + posting.id()))
                    .body(PostingResponse.of(posting));
        }

        @GetMapping(path = "/{id}", version = "1")
        PostingResponse find(@PathVariable long id) {
            return PostingResponse.of(postings.find(id));
        }
    }
}
