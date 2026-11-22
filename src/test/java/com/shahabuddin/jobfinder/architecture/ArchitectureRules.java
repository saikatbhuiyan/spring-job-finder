package com.shahabuddin.jobfinder.architecture;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import com.tngtech.archunit.library.GeneralCodingRules;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.stream.Stream;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
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

    private ArchitectureRules() {
    }

    /** Dependencies flow inwards: api → application → domain ← infrastructure. */
    public static final ArchRule LAYER_DEPENDENCIES = layeredArchitecture()
            .consideringOnlyDependenciesInLayers()
            .withOptionalLayers(true)
            .layer("Api").definedBy(API)
            .layer("Application").definedBy(APPLICATION)
            .layer("Domain").definedBy(DOMAIN)
            .layer("Infrastructure").definedBy(INFRASTRUCTURE)
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
        DescribedPredicate<JavaClass> inDomain = resideInAPackage(DOMAIN);
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

    /** HTTP endpoints live only in the api layer. */
    public static final ArchRule CONTROLLERS_LIVE_IN_API = classes()
            .that().areAnnotatedWith(RestController.class)
            .or().areAnnotatedWith(Controller.class)
            .should().resideInAPackage(API)
            .allowEmptyShould(true)
            .because("controllers are the HTTP adapter of a module");

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
