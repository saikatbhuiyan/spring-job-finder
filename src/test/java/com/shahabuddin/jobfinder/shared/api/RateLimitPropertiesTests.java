package com.shahabuddin.jobfinder.shared.api;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.core.NestedExceptionUtils;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/** Production defaults, startup validation, and the switch to turn the limit off. */
class RateLimitPropertiesTests {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class))
            .withUserConfiguration(RateLimitConfig.class);

    @Test
    void defaultsAre60RequestsPerMinutePerClient() {
        runner.run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(FilterRegistrationBean.class);
            RateLimitProperties properties = context.getBean(RateLimitProperties.class);
            assertThat(properties.requests()).isEqualTo(60);
            assertThat(properties.period()).isEqualTo(Duration.ofMinutes(1));
            assertThat(properties.maxClients()).isEqualTo(100_000);
            assertThat(context.getBean(FilterRegistrationBean.class).getUrlPatterns()).containsExactly("/api/*");
        });
    }

    @Test
    void canBeTurnedOff() {
        runner.withPropertyValues("jobfinder.rate-limit.enabled=false")
                .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(FilterRegistrationBean.class));
    }

    @ParameterizedTest(name = "{0}={1}")
    @CsvSource({"requests, 0", "period, 500ms", "max-clients, 0"})
    void nonsensicalValuesFailStartup(String property, String value) {
        runner.withPropertyValues("jobfinder.rate-limit." + property + "=" + value)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(NestedExceptionUtils.getMostSpecificCause(context.getStartupFailure()))
                            .hasMessageContaining("jobfinder.rate-limit." + property);
                });
    }
}
