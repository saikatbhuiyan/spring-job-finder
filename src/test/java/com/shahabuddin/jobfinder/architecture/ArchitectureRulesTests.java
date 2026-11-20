package com.shahabuddin.jobfinder.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests the architecture rules themselves. Production has few classes yet, so a rule that
 * silently matched nothing would pass forever. These tests prove that each rule
 * <b>rejects</b> a known violation and <b>accepts</b> a correctly structured module.
 */
class ArchitectureRulesTests {

    private static final JavaClasses VIOLATIONS = new ClassFileImporter().importPackages("archfixture.violations");
    private static final JavaClasses COMPLIANT = new ClassFileImporter().importPackages("archfixture.compliant");

    static Stream<Arguments> rulesAndTheViolationTheyMustCatch() {
        return Stream.of(
                Arguments.of("layer dependencies", ArchitectureRules.LAYER_DEPENDENCIES, "Job"),
                Arguments.of("domain is web-agnostic", ArchitectureRules.DOMAIN_IS_WEB_AGNOSTIC, "WebAwareJob"),
                Arguments.of("api does not use repositories", ArchitectureRules.API_DOES_NOT_USE_REPOSITORIES, "ShortcutController"),
                Arguments.of("controllers live in api", ArchitectureRules.CONTROLLERS_LIVE_IN_API, "MisplacedController"),
                Arguments.of("no field injection", ArchitectureRules.NO_FIELD_INJECTION, "FieldInjectedService"),
                Arguments.of("no standard streams", ArchitectureRules.NO_STANDARD_STREAMS, "ConsolePrinter"),
                Arguments.of("no java.util.logging", ArchitectureRules.NO_JAVA_UTIL_LOGGING, "JulLogging"));
    }

    @ParameterizedTest(name = "{0} rejects {2}")
    @MethodSource("rulesAndTheViolationTheyMustCatch")
    void ruleRejectsKnownViolation(String name, ArchRule rule, String offendingClass) {
        assertThatThrownBy(() -> rule.check(VIOLATIONS))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining(offendingClass);
    }

    @Test
    void compliantModulePassesEveryRule() {
        List<ArchRule> allRules = rulesAndTheViolationTheyMustCatch()
                .map(arguments -> (ArchRule) arguments.get()[1])
                .toList();
        for (ArchRule rule : allRules) {
            assertThatCode(() -> rule.check(COMPLIANT))
                    .as(rule.getDescription())
                    .doesNotThrowAnyException();
        }
    }
}
