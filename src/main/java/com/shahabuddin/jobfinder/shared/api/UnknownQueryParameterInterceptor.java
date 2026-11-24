package com.shahabuddin.jobfinder.shared.api;

import com.shahabuddin.jobfinder.shared.PageQuery;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.BeanUtils;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.annotation.BindParam;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.beans.PropertyDescriptor;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.RecordComponent;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Rejects query parameters the endpoint doesn't declare (see docs/adr/0011-filtering-and-search.md).
 * Spring MVC ignores them, so a typo ({@code ?titel=java}) silently returned every job, unfiltered.
 * The query-parameter equivalent of rejecting unknown JSON properties (ADR 0005).
 *
 * <p>The known names of an endpoint are derived from its method signature, once per method:
 * {@code @RequestParam} names (and unannotated simple parameters, which Spring binds by name), the
 * properties of objects bound from query parameters ({@code @ModelAttribute}, or an unannotated
 * record such as a filter; {@code @BindParam} names respected), and {@code page}, {@code size},
 * {@code sort} for a {@link PageQuery}. An endpoint with a {@code @RequestParam Map} accepts any
 * parameter. Only the query string is checked (not form bodies).
 */
final class UnknownQueryParameterInterceptor implements HandlerInterceptor {

    /** Per handler method: the names it accepts, or empty if it accepts any name. */
    private final Map<Method, Optional<Set<String>>> knownParameters = new ConcurrentHashMap<>();

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String query = request.getQueryString();
        if (query == null || query.isEmpty() || !(handler instanceof HandlerMethod method)) {
            return true;
        }
        Optional<Set<String>> known = knownParameters.computeIfAbsent(method.getMethod(), m -> knownParameters(method));
        if (known.isEmpty()) {
            return true;
        }
        Set<String> unknown = new TreeSet<>(queryParameterNames(query));
        unknown.removeAll(known.get());
        if (!unknown.isEmpty()) {
            throw new InvalidRequestParametersException(unknown.stream()
                    .map(name -> Violation.atParameter(name, Violation.UNKNOWN_PARAMETER, "is not a known parameter"))
                    .toList());
        }
        return true;
    }

    private static Optional<Set<String>> knownParameters(HandlerMethod method) {
        Set<String> names = new HashSet<>();
        for (MethodParameter parameter : method.getMethodParameters()) {
            Class<?> type = parameter.getParameterType();
            RequestParam requestParam = parameter.getParameterAnnotation(RequestParam.class);
            if (requestParam != null) {
                if (Map.class.isAssignableFrom(type)) {
                    return Optional.empty();
                }
                names.add(!requestParam.name().isEmpty() ? requestParam.name() : parameter.getParameterName());
            } else if (PageQuery.class.equals(type)) {
                names.addAll(List.of(PageQueryArgumentResolver.PAGE, PageQueryArgumentResolver.SIZE, PageQueryArgumentResolver.SORT));
            } else if (parameter.hasParameterAnnotation(ModelAttribute.class)
                    || (!hasBindingAnnotation(parameter) && isQueryObject(type))) {
                names.addAll(bindableProperties(type));
            } else if (!hasBindingAnnotation(parameter) && BeanUtils.isSimpleProperty(type)) {
                // Spring binds an unannotated simple parameter like @RequestParam(required = false).
                names.add(parameter.getParameterName());
            }
        }
        return Optional.of(Set.copyOf(names));
    }

    /**
     * Whether a Spring MVC annotation says where the value comes from ({@code @RequestBody},
     * {@code @PathVariable}, {@code @RequestHeader}...). Other annotations, such as {@code @Valid},
     * don't change how Spring binds the parameter.
     */
    private static boolean hasBindingAnnotation(MethodParameter parameter) {
        return Arrays.stream(parameter.getParameterAnnotations())
                .anyMatch(annotation -> annotation.annotationType().getPackageName()
                        .equals(RequestParam.class.getPackageName()));
    }

    /** An object of our own bound from query parameters (a filter record), not a framework type. */
    private static boolean isQueryObject(Class<?> type) {
        return !BeanUtils.isSimpleProperty(type) && !type.getName().startsWith("java.")
                && !type.getName().startsWith("jakarta.") && !type.getName().startsWith("org.springframework.");
    }

    private static Set<String> bindableProperties(Class<?> type) {
        if (type.isRecord()) {
            // @BindParam targets parameters only, so on a record component Java keeps it on the
            // canonical constructor's parameter (where Spring reads it), not on the component.
            RecordComponent[] components = type.getRecordComponents();
            Class<?>[] componentTypes = Arrays.stream(components).map(RecordComponent::getType).toArray(Class<?>[]::new);
            Parameter[] constructorParameters = canonicalConstructor(type, componentTypes).getParameters();
            Set<String> names = new HashSet<>();
            for (int i = 0; i < components.length; i++) {
                BindParam bindParam = constructorParameters[i].getAnnotation(BindParam.class);
                names.add(bindParam != null ? bindParam.value() : components[i].getName());
            }
            return names;
        }
        Set<String> names = new HashSet<>();
        for (PropertyDescriptor property : BeanUtils.getPropertyDescriptors(type)) {
            if (property.getWriteMethod() != null) {
                names.add(property.getName());
            }
        }
        return names;
    }

    private static Constructor<?> canonicalConstructor(Class<?> record, Class<?>[] componentTypes) {
        try {
            return record.getDeclaredConstructor(componentTypes);
        } catch (NoSuchMethodException impossible) {
            throw new IllegalStateException("Record without canonical constructor: " + record.getName(), impossible);
        }
    }

    private static Set<String> queryParameterNames(String query) {
        Set<String> names = new HashSet<>();
        Arrays.stream(query.split("&")).forEach(pair -> {
            int equals = pair.indexOf('=');
            String name = URLDecoder.decode(equals < 0 ? pair : pair.substring(0, equals), StandardCharsets.UTF_8);
            if (!name.isEmpty()) {
                names.add(name);
            }
        });
        return names;
    }
}
