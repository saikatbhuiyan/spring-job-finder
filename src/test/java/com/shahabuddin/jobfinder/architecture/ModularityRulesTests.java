package com.shahabuddin.jobfinder.architecture;

import modfixture.ModFixtureApplication;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.core.Violations;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves Spring Modulith's verification actually catches boundary violations, using a fixture
 * application (modfixture) whose modules break the rules on purpose.
 *
 * <p>Uses {@code detectViolations()} rather than {@code verify()}: ApplicationModules instances
 * are cached statically and {@code verify()} only runs once per instance, so a second
 * {@code verify()} call in the same JVM would silently pass.
 */
class ModularityRulesTests {

    // The fixture lives in test sources, which ApplicationModules.of(Class) skips by default,
    // so import everything (the default for production verification is DoNotIncludeTests).
    private final Violations violations =
            ApplicationModules.of(ModFixtureApplication.class, location -> true).detectViolations();

    @Test
    void rejectsAccessToAnotherModulesInternals() {
        assertThat(violations.hasViolations()).isTrue();
        assertThat(violations.getMessage())
                .contains("non-exposed type")
                .contains("CompanyRatingCalculator");
    }

    @Test
    void rejectsCyclesBetweenModules() {
        assertThat(violations.getMessage()).contains("Cycle detected");
    }
}
