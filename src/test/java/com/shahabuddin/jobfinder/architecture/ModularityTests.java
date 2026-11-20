package com.shahabuddin.jobfinder.architecture;

import com.shahabuddin.jobfinder.JobFinderApplication;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.docs.Documenter;

/**
 * Verifies module boundaries with Spring Modulith: each direct sub-package of
 * {@code com.shahabuddin.jobfinder} (job, company, ...) is a module. Modules may only use each
 * other's top-level (public API) package, never internal sub-packages, and must not form cycles.
 */
class ModularityTests {

    private final ApplicationModules modules = ApplicationModules.of(JobFinderApplication.class);

    @Test
    void modulesRespectBoundaries() {
        modules.verify();
    }

    @Test
    void writeModuleDocumentation() {
        // Generates PlantUML component diagrams and module canvases into target/spring-modulith-docs
        new Documenter(modules).writeDocumentation();
    }
}
