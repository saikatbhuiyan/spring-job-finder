package com.shahabuddin.jobfinder.shared.api;

import com.github.benmanes.caffeine.cache.Ticker;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

/**
 * Idempotency keys on {@code /api/**} (docs/adr/0014-idempotency-keys.md).
 *
 * <p>Filter order: after the trace ID filter (its problems carry {@code traceId}) and the rate limit (a
 * rejected request must not claim a key), and after Spring Security once it is added (its chain runs at
 * {@code -100}): the key will be scoped by the authenticated user, and an unauthenticated request must
 * not reach the store at all.
 *
 * <p>Deliberately <b>not</b> a {@code WebMvcConfigurer}: {@code @WebMvcTest} slices include every
 * {@code WebMvcConfigurer} with all its beans, and the filter's {@code MeterRegistry} isn't part of the
 * MVC slice, so every controller slice test in the application failed to start (verified). The key
 * interceptor, which belongs to Spring MVC, is registered by {@link IdempotencyKeyConfig}.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(IdempotencyProperties.class)
class IdempotencyConfig {

    static final int ORDER = 0;

    private static final Logger log = LoggerFactory.getLogger(IdempotencyConfig.class);

    @Bean
    IdempotencyStore idempotencyStore(IdempotencyProperties properties) {
        log.info("Idempotency keys: kept {} h in memory (at most {} MB of responses, requests up to {} KB)",
                properties.keyTtl().toHours(), properties.maxStoredSize().toMegabytes(),
                properties.maxRequestSize().toKilobytes());
        return new InMemoryIdempotencyStore(properties.keyTtl(), properties.maxStoredSize().toBytes(), Ticker.systemTicker());
    }

    @Bean
    FilterRegistrationBean<IdempotencyFilter> idempotencyFilter(IdempotencyStore store, IdempotencyProperties properties,
                                                               JsonMapper json, MeterRegistry meters) {
        FilterRegistrationBean<IdempotencyFilter> registration =
                new FilterRegistrationBean<>(new IdempotencyFilter(store, properties, json, meters));
        registration.addUrlPatterns("/api/*");
        registration.setOrder(ORDER);
        return registration;
    }
}
