package com.shahabuddin.jobfinder.shared;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares which properties clients may sort a paged endpoint by (see {@link PageQuery} and
 * docs/adr/0010-pagination-and-sorting.md).
 *
 * <pre>{@code
 * @GetMapping
 * PagedModel<JobSummary> search(@SortableBy(value = {"createdAt", "salary", "title"},
 *                                           defaultSort = "createdAt,desc") PageQuery page)
 * }</pre>
 *
 * An allowlist, because sorting by an arbitrary property lets clients order results by columns they
 * can't read (inferring their values), and sort by unindexed columns. Without this annotation an
 * endpoint can't be sorted by the client at all. Declarations are checked at startup.
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface SortableBy {

    /** Properties a client may sort by, as named in the API (they must exist on what is sorted). */
    String[] value();

    /** Order when the client sends no {@code sort}, e.g. {@code "createdAt,desc"}. Must be sortable. */
    String[] defaultSort() default {};
}
