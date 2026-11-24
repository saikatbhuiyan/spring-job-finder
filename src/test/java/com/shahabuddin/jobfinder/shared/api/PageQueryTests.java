package com.shahabuddin.jobfinder.shared.api;

import com.shahabuddin.jobfinder.shared.PageQuery;
import com.shahabuddin.jobfinder.shared.SortableBy;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/** {@link PageQuery} invariants, and that a wrong {@link SortableBy} declaration fails startup. */
class PageQueryTests {

    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(WebMvcAutoConfiguration.class))
            .withUserConfiguration(PaginationConfig.class);

    @Test
    void tiebreakerIsAppendedOnce() {
        assertThat(new PageQuery(0, 20, Sort.by("title")).sort()).containsExactly(Sort.Order.asc("title"), Sort.Order.asc("id"));
        assertThat(new PageQuery(0, 20, Sort.by(Sort.Order.desc("id"))).sort()).containsExactly(Sort.Order.desc("id"));
    }

    @Test
    void invalidQueriesCantBeConstructed() {
        // The resolver reports these as 400s; code that builds a PageQuery itself can't bypass the limits.
        assertThatIllegalArgumentException().isThrownBy(() -> new PageQuery(-1, 20, Sort.unsorted()));
        assertThatIllegalArgumentException().isThrownBy(() -> new PageQuery(0, 0, Sort.unsorted()));
        assertThatIllegalArgumentException().isThrownBy(() -> new PageQuery(0, 101, Sort.unsorted()));
        assertThatIllegalArgumentException().isThrownBy(() -> new PageQuery(101, 100, Sort.unsorted()));
    }

    @Test
    void validDeclarationsStart() {
        runner.withBean(ValidController.class).run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void defaultSortOutsideTheAllowlistFailsStartup() {
        runner.withBean(DefaultNotSortableController.class).run(context -> {
            assertThat(context).hasFailed();
            assertThat(NestedExceptionUtils.getMostSpecificCause(context.getStartupFailure()))
                    .hasMessageContaining("default sort 'createdAt,desc' must be")
                    .hasMessageContaining("DefaultNotSortableController");
        });
    }

    @Test
    void sortableByOnAnotherParameterTypeFailsStartup() {
        runner.withBean(WrongTypeController.class).run(context -> {
            assertThat(context).hasFailed();
            assertThat(NestedExceptionUtils.getMostSpecificCause(context.getStartupFailure()))
                    .hasMessageContaining("must annotate a PageQuery parameter");
        });
    }

    @RestController
    static class ValidController {
        @GetMapping("/valid")
        String search(@SortableBy(value = {"title", "createdAt"}, defaultSort = "createdAt,desc") PageQuery page) {
            return "ok";
        }
    }

    @RestController
    static class DefaultNotSortableController {
        @GetMapping("/default-not-sortable")
        String search(@SortableBy(value = "title", defaultSort = "createdAt,desc") PageQuery page) {
            return "ok";
        }
    }

    @RestController
    static class WrongTypeController {
        @GetMapping("/wrong-type")
        String search(@SortableBy("title") String page) {
            return "ok";
        }
    }
}
