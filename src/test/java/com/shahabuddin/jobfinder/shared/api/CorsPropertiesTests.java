package com.shahabuddin.jobfinder.shared.api;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.web.cors.CorsConfigurationSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The CORS origin list is validated at startup: a bad value fails fast with a clear message,
 * instead of failing every browser request later (e.g. "*" turns into a 500 per request once
 * credentials are enabled).
 */
class CorsPropertiesTests {

    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withUserConfiguration(CorsConfig.class);

    @Test
    void noOriginsByDefault() {
        runner.run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(CorsConfigurationSource.class);
            assertThat(context.getBean(CorsProperties.class).allowedOrigins()).isEmpty();
        });
    }

    @Test
    void emptyPropertyMeansNoOrigins() {
        runner.withPropertyValues("jobfinder.cors.allowed-origins=")
                .run(context -> assertThat(context.getBean(CorsProperties.class).allowedOrigins()).isEmpty());
    }

    @Test
    void acceptsExactOrigins() {
        runner.withPropertyValues("jobfinder.cors.allowed-origins=https://app.example.com,http://localhost:5173,https://admin.example.com/")
                .run(context -> assertThat(context.getBean(CorsProperties.class).allowedOrigins())
                        .containsExactly("https://app.example.com", "http://localhost:5173", "https://admin.example.com/"));
    }

    @ParameterizedTest(name = "rejects \"{0}\"")
    @ValueSource(strings = {
            "*",                                 // wildcard
            "https://*.example.com",             // wildcard pattern
            "app.example.com",                   // no scheme
            "https://app.example.com/frontend",  // a path is not part of an origin
            "ftp://app.example.com",             // not http(s)
            "https://user@app.example.com",      // user info
            "https://app.example.com?x=1"        // query
    })
    void rejectsValuesThatAreNotOrigins(String origin) {
        runner.withPropertyValues("jobfinder.cors.allowed-origins=" + origin)
                .run(context -> assertThat(context).hasFailed().getFailure()
                        .rootCause().hasMessageContaining("Invalid CORS origin '" + origin + "'"));
    }

    @Test
    void rejectsNegativeMaxAge() {
        runner.withPropertyValues("jobfinder.cors.max-age=-1s")
                .run(context -> assertThat(context).hasFailed().getFailure()
                        .rootCause().hasMessageContaining("max-age must not be negative"));
    }
}
