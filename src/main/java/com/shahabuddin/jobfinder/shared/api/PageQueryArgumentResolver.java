package com.shahabuddin.jobfinder.shared.api;

import com.shahabuddin.jobfinder.shared.PageQuery;
import com.shahabuddin.jobfinder.shared.SortableBy;
import org.jspecify.annotations.Nullable;
import org.springframework.core.MethodParameter;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Binds and validates a {@link PageQuery} controller parameter from {@code page}, {@code size} and
 * {@code sort} (see docs/adr/0010-pagination-and-sorting.md). Every problem is collected and
 * reported at once as a {@code validation-error}; nothing is silently replaced by a default.
 */
final class PageQueryArgumentResolver implements HandlerMethodArgumentResolver {

    static final String PAGE = "page";
    static final String SIZE = "size";
    static final String SORT = "sort";

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return PageQuery.class.equals(parameter.getParameterType());
    }

    @Override
    public PageQuery resolveArgument(MethodParameter parameter, @Nullable ModelAndViewContainer mavContainer,
                                     NativeWebRequest request, @Nullable WebDataBinderFactory binderFactory) {
        SortableBy sortable = parameter.getParameterAnnotation(SortableBy.class);
        List<Violation> violations = new ArrayList<>();

        Integer page = integer(request.getParameter(PAGE), PAGE, 0, violations);
        Integer size = integer(request.getParameter(SIZE), SIZE, PageQuery.DEFAULT_SIZE, violations);
        if (page != null && page < 0) {
            violations.add(Violation.atParameter(PAGE, "Min", "must be greater than or equal to 0"));
        }
        if (size != null && size < 1) {
            violations.add(Violation.atParameter(SIZE, "Min", "must be greater than or equal to 1"));
        }
        if (size != null && size > PageQuery.MAX_SIZE) {
            violations.add(Violation.atParameter(SIZE, "Max", "must be less than or equal to " + PageQuery.MAX_SIZE));
        }
        if (page != null && size != null && violations.isEmpty() && (long) page * size > PageQuery.MAX_OFFSET) {
            violations.add(Violation.atParameter(PAGE, "Max", "is too deep: page × size must not exceed "
                    + PageQuery.MAX_OFFSET + "; narrow the search instead"));
        }
        Sort sort = sort(request.getParameterValues(SORT), sortable, violations);

        if (page == null || size == null || !violations.isEmpty()) {
            throw new InvalidRequestParametersException(violations);
        }
        return new PageQuery(page, size, sort);
    }

    /** The parameter's value, its default when absent, or {@code null} (and a violation) if not an integer. */
    private static @Nullable Integer integer(@Nullable String value, String name, int defaultValue, List<Violation> violations) {
        if (value == null) {
            return defaultValue;
        }
        try {
            return Integer.valueOf(value.trim());
        } catch (NumberFormatException notAnInteger) {
            violations.add(Violation.atParameter(name, Violation.INVALID_TYPE, "has an invalid value or type"));
            return null;
        }
    }

    private static Sort sort(String @Nullable [] values, @Nullable SortableBy sortable, List<Violation> violations) {
        if (values == null || values.length == 0) {
            return sortable == null ? Sort.unsorted() : declaredSort(sortable.defaultSort());
        }
        List<String> allowed = sortable == null ? List.of() : Arrays.asList(sortable.value());
        List<Sort.Order> orders = new ArrayList<>();
        for (String value : values) {
            Optional<Sort.Order> order = parse(value);
            if (order.isEmpty()) {
                violations.add(Violation.atParameter(SORT, Violation.INVALID_VALUE,
                        "must be 'property' or 'property,asc' or 'property,desc'"));
            } else if (!allowed.contains(order.get().getProperty())) {
                violations.add(Violation.atParameter(SORT, Violation.INVALID_VALUE, allowed.isEmpty()
                        ? "is not supported: this endpoint can't be sorted"
                        : "is not a sortable property; sortable: " + String.join(", ", allowed.stream().sorted().toList())));
            } else {
                orders.add(order.get());
            }
        }
        return Sort.by(orders);
    }

    /** {@code property} or {@code property,asc|desc} (direction case-insensitive). */
    private static Optional<Sort.Order> parse(String value) {
        String[] parts = value.split(",", -1);
        String property = parts[0].trim();
        if (property.isEmpty() || parts.length > 2) {
            return Optional.empty();
        }
        if (parts.length == 1) {
            return Optional.of(Sort.Order.asc(property));
        }
        return switch (parts[1].trim().toLowerCase(Locale.ROOT)) {
            case "asc" -> Optional.of(Sort.Order.asc(property));
            case "desc" -> Optional.of(Sort.Order.desc(property));
            default -> Optional.empty();
        };
    }

    private static Sort declaredSort(String[] declared) {
        return Sort.by(Arrays.stream(declared)
                .map(value -> parse(value).orElseThrow())
                .toList());
    }

    /**
     * Fails fast on a declaration that could never work, e.g. a default sort that isn't sortable.
     * Called for every {@code @SortableBy} parameter at startup ({@link PaginationConfig}).
     */
    static void checkDeclaration(SortableBy sortable, Class<?> parameterType, String where) {
        if (!PageQuery.class.equals(parameterType)) {
            throw new IllegalStateException("@SortableBy on " + where + " must annotate a PageQuery parameter");
        }
        if (sortable.value().length == 0) {
            throw new IllegalStateException("@SortableBy on " + where + " lists no sortable properties");
        }
        List<String> allowed = Arrays.asList(sortable.value());
        for (String declared : sortable.defaultSort()) {
            Optional<Sort.Order> order = parse(declared);
            if (order.isEmpty() || !allowed.contains(order.get().getProperty())) {
                throw new IllegalStateException("@SortableBy on " + where + ": default sort '" + declared
                        + "' must be 'property[,asc|desc]' with a property from " + allowed);
            }
        }
    }
}
