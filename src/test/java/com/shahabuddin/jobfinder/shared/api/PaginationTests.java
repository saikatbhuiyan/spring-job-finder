package com.shahabuddin.jobfinder.shared.api;

import com.shahabuddin.jobfinder.shared.PageQuery;
import com.shahabuddin.jobfinder.shared.SortableBy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PagedModel;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Comparator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Locks in the paging contract of docs/adr/0010-pagination-and-sorting.md over real HTTP: defaults,
 * the sort allowlist, the tiebreaker, limits, and that invalid input is rejected (all at once)
 * instead of silently replaced, as Spring Data's own {@code Pageable} binding does.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
class PaginationTests {

    private static final String VALIDATION_ERROR =
            "https://github.com/saikatbhuiyan/spring-job-finder/blob/main/docs/problems.md#validation-error";

    @Autowired
    private RestTestClient client;

    // --- Binding ---------------------------------------------------------------------------------

    @Test
    void defaultsAreFirstPageOf20InTheDeclaredOrderWithTheTiebreaker() {
        client.get().uri("/api/v1/paging-probe/echo")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.page").isEqualTo(0)
                .jsonPath("$.size").isEqualTo(20)
                .jsonPath("$.sort").isEqualTo(List.of("createdAt: DESC", "id: ASC"));
    }

    @Test
    void explicitPageSizeAndSortAreUsedInTheGivenOrder() {
        client.get().uri("/api/v1/paging-probe/echo?page=2&size=5&sort=salary,DESC&sort=title")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.page").isEqualTo(2)
                .jsonPath("$.size").isEqualTo(5)
                .jsonPath("$.sort").isEqualTo(List.of("salary: DESC", "title: ASC", "id: ASC"));
    }

    @Test
    void everyInvalidValueIsReportedAtOnceAndNothingIsSilentlyReplaced() {
        // Spring Data's Pageable binding answers all of these with 200 and its defaults (or a 500
        // for the unknown property), verified in learning/35.
        client.get().uri("/api/v1/paging-probe/echo?page=-1&size=500&sort=secretNotes&sort=title,sideways")
                .exchange()
                .expectStatus().isBadRequest()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.type").isEqualTo(VALIDATION_ERROR)
                .jsonPath("$.errors.length()").isEqualTo(4)
                .jsonPath("$.errors[0].parameter").isEqualTo("page")
                .jsonPath("$.errors[0].code").isEqualTo("Min")
                .jsonPath("$.errors[1].parameter").isEqualTo("size")
                .jsonPath("$.errors[1].code").isEqualTo("Max")
                .jsonPath("$.errors[1].detail").isEqualTo("must be less than or equal to 100")
                .jsonPath("$.errors[2].parameter").isEqualTo("sort")
                .jsonPath("$.errors[2].code").isEqualTo("InvalidValue")
                .jsonPath("$.errors[2].detail").isEqualTo("is not a sortable property; sortable: createdAt, salary, title")
                .jsonPath("$.errors[3].parameter").isEqualTo("sort")
                .jsonPath("$.errors[3].detail").isEqualTo("must be 'property' or 'property,asc' or 'property,desc'")
                .consumeWith(result -> assertThat(new String(result.getResponseBodyContent())).doesNotContain("secretNotes"));
    }

    @Test
    void nonNumbersAreInvalidTypesWithoutEchoingTheValue() {
        client.get().uri("/api/v1/paging-probe/echo?page=xyz&size=x")
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.errors.length()").isEqualTo(2)
                .jsonPath("$.errors[0].parameter").isEqualTo("page")
                .jsonPath("$.errors[0].code").isEqualTo("InvalidType")
                .jsonPath("$.errors[1].parameter").isEqualTo("size")
                .jsonPath("$.errors[1].code").isEqualTo("InvalidType")
                // "xyz", not a hex-only value: the body's random traceId must not match it by chance.
                .consumeWith(result -> assertThat(new String(result.getResponseBodyContent())).doesNotContain("xyz"));
    }

    @ParameterizedTest(name = "page={0}&size=100")
    @ValueSource(strings = {"101", "2147483647"})
    void deepOffsetsAreRejected(String page) {
        // 2147483647 × 100 overflowed JPA's int offset: a 500 with Spring Data's own binding.
        client.get().uri("/api/v1/paging-probe/echo?page=" + page + "&size=100")
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.errors[0].parameter").isEqualTo("page")
                .jsonPath("$.errors[0].code").isEqualTo("Max")
                .jsonPath("$.errors[0].detail").isEqualTo("is too deep: page × size must not exceed 10000; narrow the search instead");
    }

    /** Found by mutation testing: no test used size=1, so {@code size < 1} vs {@code size <= 1} went unnoticed. */
    @ParameterizedTest(name = "size={0}")
    @ValueSource(ints = {1, 100})
    void theSmallestAndLargestPageSizesAreAccepted(int size) {
        client.get().uri("/api/v1/paging-probe/echo?page=0&size=" + size)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.size").isEqualTo(size);
    }

    @Test
    void theDeepestAllowedPageIsAccepted() {
        client.get().uri("/api/v1/paging-probe/echo?page=100&size=100")
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void anEndpointWithoutSortableByCantBeSortedButIsStillTotallyOrdered() {
        client.get().uri("/api/v1/paging-probe/unsortable")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.sort").isEqualTo(List.of("id: ASC"));
        client.get().uri("/api/v1/paging-probe/unsortable?sort=title")
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.errors[0].parameter").isEqualTo("sort")
                .jsonPath("$.errors[0].detail").isEqualTo("is not supported: this endpoint can't be sorted");
    }

    // --- Response --------------------------------------------------------------------------------

    @Test
    void pageResponseHasAStableShapeAndEqualSortValuesAreOrderedByTheTiebreaker() {
        // Salaries 5000 (ids 2, 4), 7000 (ids 1, 3, 5): sorting by salary alone leaves ties in
        // an undefined order, which is how rows get skipped or repeated across pages.
        client.get().uri("/api/v1/paging-probe/jobs?size=2&page=1&sort=salary")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.content[*].id").isEqualTo(List.of(1, 3))
                .jsonPath("$.page.size").isEqualTo(2)
                .jsonPath("$.page.number").isEqualTo(1)
                .jsonPath("$.page.totalElements").isEqualTo(5)
                .jsonPath("$.page.totalPages").isEqualTo(3)
                .jsonPath("$.pageable").doesNotExist();
    }

    @Test
    void aPageAfterTheLastIsEmptyNotAnError() {
        client.get().uri("/api/v1/paging-probe/jobs?size=2&page=7")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.content.length()").isEqualTo(0)
                .jsonPath("$.page.totalElements").isEqualTo(5);
    }

    // --- Test-only endpoints -----------------------------------------------------------------------

    record EchoResponse(int page, int size, List<String> sort) {
        static EchoResponse of(PageQuery query) {
            return new EchoResponse(query.page(), query.size(), query.sort().stream().map(Sort.Order::toString).toList());
        }
    }

    record JobRow(long id, String title, int salary) {
    }

    private static final List<JobRow> JOBS = List.of(
            new JobRow(1, "Backend engineer", 7000),
            new JobRow(2, "Frontend engineer", 5000),
            new JobRow(3, "Data engineer", 7000),
            new JobRow(4, "QA engineer", 5000),
            new JobRow(5, "SRE", 7000));

    /** What a repository does with a Pageable, for in-memory rows. */
    private static Page<JobRow> findAll(Pageable pageable) {
        Comparator<JobRow> order = (a, b) -> 0;
        for (Sort.Order sortOrder : pageable.getSort()) {
            Comparator<JobRow> property = switch (sortOrder.getProperty()) {
                case "id" -> Comparator.comparingLong(JobRow::id);
                case "title" -> Comparator.comparing(JobRow::title);
                case "salary" -> Comparator.comparingInt(JobRow::salary);
                default -> throw new IllegalArgumentException(sortOrder.getProperty());
            };
            order = order.thenComparing(sortOrder.isAscending() ? property : property.reversed());
        }
        List<JobRow> sorted = JOBS.stream().sorted(order).toList();
        int from = (int) Math.min(pageable.getOffset(), sorted.size());
        int to = Math.min(from + pageable.getPageSize(), sorted.size());
        return new PageImpl<>(sorted.subList(from, to), pageable, sorted.size());
    }

    @TestConfiguration
    static class ProbeConfig {
        @Bean
        PagingProbeController pagingProbeController() {
            return new PagingProbeController();
        }
    }

    @RestController
    @RequestMapping("/api/{version}/paging-probe")
    static class PagingProbeController {

        @GetMapping(path = "/echo", version = "1")
        EchoResponse echo(@SortableBy(value = {"title", "salary", "createdAt"}, defaultSort = "createdAt,desc") PageQuery query) {
            return EchoResponse.of(query);
        }

        @GetMapping(path = "/unsortable", version = "1")
        EchoResponse unsortable(PageQuery query) {
            return EchoResponse.of(query);
        }

        @GetMapping(path = "/jobs", version = "1")
        PagedModel<JobRow> jobs(@SortableBy({"title", "salary"}) PageQuery query) {
            return new PagedModel<>(findAll(query.toPageable()));
        }
    }
}
