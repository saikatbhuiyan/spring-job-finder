package com.shahabuddin.jobfinder.shared.api;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.util.unit.DataSize;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/** Production defaults and startup validation of {@code jobfinder.idempotency.*} (docs/adr/0014). */
class IdempotencyPropertiesTests {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class))
            .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
            .withUserConfiguration(IdempotencyConfig.class);

    @Test
    void defaultsKeepKeysForADay() {
        runner.run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(IdempotencyStore.class);
            IdempotencyProperties properties = context.getBean(IdempotencyProperties.class);
            assertThat(properties.keyTtl()).isEqualTo(Duration.ofHours(24));
            assertThat(properties.maxRequestSize()).isEqualTo(DataSize.ofMegabytes(1));
            assertThat(properties.maxStoredSize()).isEqualTo(DataSize.ofMegabytes(64));
            FilterRegistrationBean<?> filter = context.getBean(FilterRegistrationBean.class);
            assertThat(filter.getUrlPatterns()).containsExactly("/api/*");
            assertThat(filter.getOrder()).isGreaterThan(RateLimitConfig.ORDER).isGreaterThan(-100);
        });
    }

    @ParameterizedTest(name = "{0}={1}")
    @CsvSource({"key-ttl, 30s", "max-request-size, 512B", "max-request-size, 65MB", "max-stored-size, 512KB"})
    void nonsensicalValuesFailStartup(String property, String value) {
        runner.withPropertyValues("jobfinder.idempotency." + property + "=" + value)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(NestedExceptionUtils.getMostSpecificCause(context.getStartupFailure()))
                            .hasMessageContaining("jobfinder.idempotency." + property);
                });
    }
}
