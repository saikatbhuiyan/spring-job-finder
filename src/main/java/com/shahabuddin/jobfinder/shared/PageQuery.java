package com.shahabuddin.jobfinder.shared;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/**
 * A validated request for one page of results: the API's paging contract (see
 * docs/adr/0010-pagination-and-sorting.md). Controllers declare a {@code PageQuery} parameter
 * (optionally with {@link SortableBy}); it is bound from the query parameters
 *
 * <pre>
 *   ?page=0&amp;size=20&amp;sort=createdAt,desc&amp;sort=title
 * </pre>
 *
 * and passed on to use cases as a Spring Data {@link Pageable}. Invalid input is rejected with a
 * {@code 400 validation-error}, never silently replaced by defaults as Spring Data's own binding does.
 *
 * <ul>
 *   <li>{@code page}: zero-based, default 0.</li>
 *   <li>{@code size}: 1 to {@value #MAX_SIZE}, default {@value #DEFAULT_SIZE}.</li>
 *   <li>{@code page × size} must not exceed {@value #MAX_OFFSET}: deep offsets get slower with every
 *       page (the database still reads every skipped row). Narrow the search instead.</li>
 *   <li>{@code sort}: {@code property} or {@code property,asc|desc}, repeatable; only properties
 *       allowed by {@link SortableBy}. {@value #TIEBREAKER} is always appended, so the order is total
 *       and rows don't move between pages when sort values are equal.</li>
 * </ul>
 *
 * @param page zero-based page number
 * @param size page size
 * @param sort the complete order, including the tiebreaker
 */
public record PageQuery(int page, int size, Sort sort) {

    public static final int DEFAULT_SIZE = 20;
    public static final int MAX_SIZE = 100;
    public static final int MAX_OFFSET = 10_000;
    public static final String TIEBREAKER = "id";

    public PageQuery {
        if (page < 0 || size < 1 || size > MAX_SIZE || (long) page * size > MAX_OFFSET) {
            throw new IllegalArgumentException("Invalid page query: page " + page + ", size " + size);
        }
        if (sort.getOrderFor(TIEBREAKER) == null) {
            sort = sort.and(Sort.by(TIEBREAKER));
        }
    }

    /** The same query as Spring Data's paging request, for use cases and repositories. */
    public Pageable toPageable() {
        return PageRequest.of(page, size, sort);
    }
}
