package com.shahabuddin.jobfinder.architecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * Applies the architecture rules to the production code. Runs with {@code mvn test}; any
 * violation fails the build with a message naming the offending class and dependency.
 */
@AnalyzeClasses(packages = "com.shahabuddin.jobfinder", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTests {

    @ArchTest
    static final ArchRule layerDependencies = ArchitectureRules.LAYER_DEPENDENCIES;

    @ArchTest
    static final ArchRule domainIsWebAgnostic = ArchitectureRules.DOMAIN_IS_WEB_AGNOSTIC;

    @ArchTest
    static final ArchRule apiDoesNotUseRepositories = ArchitectureRules.API_DOES_NOT_USE_REPOSITORIES;

    @ArchTest
    static final ArchRule endpointsDoNotExposeDomainTypes = ArchitectureRules.ENDPOINTS_DO_NOT_EXPOSE_DOMAIN_TYPES;

    @ArchTest
    static final ArchRule controllersLiveInApi = ArchitectureRules.CONTROLLERS_LIVE_IN_API;

    @ArchTest
    static final ArchRule controllerAdviceOnlyInSharedApi = ArchitectureRules.CONTROLLER_ADVICE_ONLY_IN_SHARED_API;

    @ArchTest
    static final ArchRule noFieldInjection = ArchitectureRules.NO_FIELD_INJECTION;

    @ArchTest
    static final ArchRule noStandardStreams = ArchitectureRules.NO_STANDARD_STREAMS;

    @ArchTest
    static final ArchRule noJavaUtilLogging = ArchitectureRules.NO_JAVA_UTIL_LOGGING;
}
