package com.shahabuddin.jobfinder.architecture;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.domain.JavaParameter;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import com.tngtech.archunit.library.GeneralCodingRules;
import jakarta.validation.Valid;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Controller;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static com.tngtech.archunit.base.DescribedPredicate.not;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage;
import static com.tngtech.archunit.core.domain.properties.CanBeAnnotated.Predicates.annotatedWith;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;

/**
 * The layer rules every feature module follows (see docs/adr/0001-modular-clean-architecture.md).
 *
 * <pre>
 *   api ──▶ application ──▶ domain ◀── infrastructure
 * </pre>
 *
 * Rules are written against package patterns ({@code ..api..}, {@code ..domain..}), so they apply
 * to every module ({@code job}, {@code company}, ...) without listing modules here.
 * {@code allowEmptyShould(true)} lets the build pass while a layer has no classes yet.
 */
public final class ArchitectureRules {

    private static final String API = "..api..";
    private static final String APPLICATION = "..application..";
    private static final String DOMAIN = "..domain..";
    private static final String INFRASTRUCTURE = "..infrastructure..";

    /**
     * Libraries can have packages named like our layers: Spring Data's paging types live in
     * {@code org.springframework.data.domain}. Layer patterns must only ever match our own code.
     */
    private static final DescribedPredicate<JavaClass> LIBRARY = resideInAnyPackage(
            "java..", "javax..", "jakarta..", "org.springframework..", "tools.jackson..", "com.fasterxml..")
            .as("library code");

    private ArchitectureRules() {
    }

    private static DescribedPredicate<JavaClass> ours(String packagePattern) {
        return resideInAPackage(packagePattern).and(not(LIBRARY)).as("our " + packagePattern);
    }

    /** Dependencies flow inwards: api → application → domain ← infrastructure. */
    public static final ArchRule LAYER_DEPENDENCIES = layeredArchitecture()
            .consideringOnlyDependenciesInLayers()
            .withOptionalLayers(true)
            .layer("Api").definedBy(ours(API))
            .layer("Application").definedBy(ours(APPLICATION))
            .layer("Domain").definedBy(ours(DOMAIN))
            .layer("Infrastructure").definedBy(ours(INFRASTRUCTURE))
            .whereLayer("Api").mayNotBeAccessedByAnyLayer()
            .whereLayer("Application").mayOnlyBeAccessedByLayers("Api", "Infrastructure")
            .whereLayer("Domain").mayOnlyBeAccessedByLayers("Api", "Application", "Infrastructure")
            .whereLayer("Infrastructure").mayNotBeAccessedByAnyLayer()
            .allowEmptyShould(true)
            .because("the domain is the core: outer layers depend on it, never the other way round");

    /** The domain knows nothing about HTTP. */
    public static final ArchRule DOMAIN_IS_WEB_AGNOSTIC = noClasses()
            .that().resideInAPackage(DOMAIN)
            .should().dependOnClassesThat().resideInAnyPackage(
                    "org.springframework.web..", "org.springframework.http..", "jakarta.servlet..")
            .allowEmptyShould(true)
            .because("business rules must not depend on the delivery mechanism (HTTP)");

    /**
     * Controllers go through the application layer. The layer rule lets api use domain types
     * (to map entities to DTOs), so this closes the gap of a controller injecting a repository
     * directly and bypassing business rules and transactions.
     */
    public static final ArchRule API_DOES_NOT_USE_REPOSITORIES = noClasses()
            .that().resideInAPackage(API)
            .should().dependOnClassesThat().haveSimpleNameEndingWith("Repository")
            .allowEmptyShould(true)
            .because("controllers must call use cases in the application layer, not repositories");

    /**
     * Endpoints speak DTOs, never domain types (see docs/adr/0005-dtos-at-the-api-boundary.md).
     * Checks the parameters and return type of every request-mapped method, including generic
     * type arguments ({@code ResponseEntity<List<Job>>} exposes {@code Job}). Private mapping
     * helpers in a controller may still take domain types; only the HTTP contract is checked.
     */
    public static final ArchRule ENDPOINTS_DO_NOT_EXPOSE_DOMAIN_TYPES = methods()
            .that().areDeclaredInClassesThat(annotatedWith(RestController.class).or(annotatedWith(Controller.class)))
            .and().areMetaAnnotatedWith(RequestMapping.class)
            .should(notHaveDomainTypesInSignature())
            .allowEmptyShould(true)
            .because("entities in an API leak internal fields, allow overposting and break on lazy loading");

    private static ArchCondition<JavaMethod> notHaveDomainTypesInSignature() {
        DescribedPredicate<JavaClass> inDomain = ours(DOMAIN);
        return new ArchCondition<>("not accept or return domain types") {
            @Override
            public void check(JavaMethod method, ConditionEvents events) {
                Stream.concat(
                                method.getReturnType().getAllInvolvedRawTypes().stream(),
                                method.getParameterTypes().stream()
                                        .flatMap(type -> type.getAllInvolvedRawTypes().stream()))
                        .filter(inDomain)
                        .distinct()
                        .forEach(type -> events.add(SimpleConditionEvent.violated(method,
                                method.getFullName() + " exposes domain type " + type.getName())));
            }
        };
    }

    /**
     * Request bodies are validated completely (see docs/adr/0009-request-validation.md). Each of these
     * mistakes compiles, starts and silently accepts invalid input (all verified):
     * <ul>
     *   <li>a {@code @RequestBody} without {@code @Valid}: no constraint is checked at all;</li>
     *   <li>a nested object or collection of objects without {@code @Valid}: its constraints are
     *       skipped. Put {@code @Valid} on the property itself ({@code @Valid List<Item> items}),
     *       which also cascades into the elements;</li>
     *   <li>a primitive property ({@code int}): a missing value can't be reported together with
     *       the other errors. Use a wrapper type with {@code @NotNull}.</li>
     * </ul>
     * Nested types are followed recursively; JDK and library types and enums are not inspected.
     */
    public static final ArchRule REQUEST_BODIES_ARE_VALIDATED = methods()
            .that().areDeclaredInClassesThat(annotatedWith(RestController.class).or(annotatedWith(Controller.class)))
            .and().areMetaAnnotatedWith(RequestMapping.class)
            .should(validateTheirRequestBodiesCompletely())
            .allowEmptyShould(true)
            .because("a missing @Valid or a primitive property silently accepts invalid input");

    private static ArchCondition<JavaMethod> validateTheirRequestBodiesCompletely() {
        return new ArchCondition<>("validate their request bodies completely") {
            @Override
            public void check(JavaMethod method, ConditionEvents events) {
                for (JavaParameter parameter : method.getParameters()) {
                    if (!parameter.isAnnotatedWith(RequestBody.class)) {
                        continue;
                    }
                    if (!isCascaded(parameter)) {
                        events.add(SimpleConditionEvent.violated(method, method.getFullName()
                                + " does not validate its @RequestBody (add @Valid)"));
                    }
                    Set<JavaClass> visited = new HashSet<>();
                    parameter.getType().getAllInvolvedRawTypes().stream()
                            .filter(ArchitectureRules::isApplicationType)
                            .forEach(type -> checkRequestType(type, visited, events));
                }
            }
        };
    }

    private static void checkRequestType(JavaClass type, Set<JavaClass> visited, ConditionEvents events) {
        if (!visited.add(type)) {
            return;
        }
        for (JavaField field : type.getFields()) {
            if (field.getModifiers().contains(JavaModifier.STATIC)) {
                continue;
            }
            if (field.getRawType().isPrimitive()) {
                events.add(SimpleConditionEvent.violated(field, field.getFullName() + " is primitive "
                        + field.getRawType().getName() + " (use a wrapper type with @NotNull)"));
            }
            List<JavaClass> nested = field.getType().getAllInvolvedRawTypes().stream()
                    .filter(ArchitectureRules::isApplicationType)
                    .toList();
            if (!nested.isEmpty() && !field.isAnnotatedWith(Valid.class)) {
                events.add(SimpleConditionEvent.violated(field, field.getFullName()
                        + " is not validated (add @Valid to cascade into it)"));
            }
            nested.forEach(nestedType -> checkRequestType(nestedType, visited, events));
        }
    }

    private static boolean isCascaded(JavaParameter parameter) {
        return parameter.isAnnotatedWith(Valid.class) || parameter.isAnnotatedWith(Validated.class);
    }

    /** A type declared by the application (not the JDK or a library) that can carry constraints. */
    private static boolean isApplicationType(JavaClass type) {
        return !type.isPrimitive() && !type.isEnum() && !type.isArray() && !LIBRARY.test(type);
    }

    /**
     * Paging goes through the API's contract (see docs/adr/0010-pagination-and-sorting.md): endpoints
     * take a {@code PageQuery}, not Spring Data's {@code Pageable} / {@code Sort}, whose web binding
     * silently replaces invalid input with defaults and lets clients sort by any property (verified).
     * They return {@code PagedModel}, not {@code Page} / {@code Slice}, whose JSON is not a stable
     * contract (ADR 0005). Generic type arguments are checked too.
     */
    public static final ArchRule ENDPOINTS_PAGE_THROUGH_THE_API_CONTRACT = methods()
            .that().areDeclaredInClassesThat(annotatedWith(RestController.class).or(annotatedWith(Controller.class)))
            .and().areMetaAnnotatedWith(RequestMapping.class)
            .should(notUseSpringDataPagingTypes())
            .allowEmptyShould(true)
            .because("Spring Data's web binding accepts invalid and unsafe paging input, and Page has no stable JSON");

    private static ArchCondition<JavaMethod> notUseSpringDataPagingTypes() {
        return new ArchCondition<>("not accept Pageable/Sort or return Page/Slice") {
            @Override
            public void check(JavaMethod method, ConditionEvents events) {
                method.getParameterTypes().stream()
                        .flatMap(type -> type.getAllInvolvedRawTypes().stream())
                        .filter(type -> type.isAssignableTo(Pageable.class) || type.isAssignableTo(Sort.class))
                        .distinct()
                        .forEach(type -> events.add(SimpleConditionEvent.violated(method, method.getFullName()
                                + " accepts " + type.getSimpleName() + " (use PageQuery)")));
                method.getReturnType().getAllInvolvedRawTypes().stream()
                        .filter(type -> type.isAssignableTo(Slice.class))
                        .distinct()
                        .forEach(type -> events.add(SimpleConditionEvent.violated(method, method.getFullName()
                                + " returns " + type.getSimpleName() + " (use PagedModel)")));
            }
        };
    }

    /** HTTP endpoints live only in the api layer. */
    public static final ArchRule CONTROLLERS_LIVE_IN_API = classes()
            .that().areAnnotatedWith(RestController.class)
            .or().areAnnotatedWith(Controller.class)
            .should().resideInAPackage(API)
            .allowEmptyShould(true)
            .because("controllers are the HTTP adapter of a module");

    /**
     * One global exception handler, in the shared kernel's api package. Spring asks
     * {@code @ControllerAdvice} beans in order and takes the first one that has <b>any</b>
     * matching handler, so the global handler's {@code Exception} catch-all would silently shadow
     * a module's own advice (verified: the module's handler never ran, the client got a 500).
     */
    public static final ArchRule CONTROLLER_ADVICE_ONLY_IN_SHARED_API = classes()
            .that().areMetaAnnotatedWith(ControllerAdvice.class)
            .should().resideInAPackage("..shared.api..")
            .allowEmptyShould(true)
            .because("a module's own advice would be shadowed by the global handler; modules throw "
                    + "DomainException subtypes instead (docs/adr/0006)");

    /** Constructor injection only (immutable, testable beans; see note 08). */
    public static final ArchRule NO_FIELD_INJECTION =
            GeneralCodingRules.NO_CLASSES_SHOULD_USE_FIELD_INJECTION.allowEmptyShould(true);

    /** Use the logging framework, not System.out / System.err. */
    public static final ArchRule NO_STANDARD_STREAMS =
            GeneralCodingRules.NO_CLASSES_SHOULD_ACCESS_STANDARD_STREAMS.allowEmptyShould(true);

    /** One logging API (SLF4J via Spring Boot), not java.util.logging. */
    public static final ArchRule NO_JAVA_UTIL_LOGGING =
            GeneralCodingRules.NO_CLASSES_SHOULD_USE_JAVA_UTIL_LOGGING.allowEmptyShould(true);
}
