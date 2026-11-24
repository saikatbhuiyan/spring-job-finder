package com.shahabuddin.jobfinder.shared.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import tools.jackson.databind.json.JsonMapper;

/**
 * Registers the {@link RateLimitFilter} for {@code /api/*} (docs/adr/0012-rate-limiting.md).
 *
 * <p>Order: after {@link CorsConfig}'s filter (so a {@code 429} carries CORS headers and a browser
 * can read it) and after Spring's observation filter (so rejected requests appear in
 * {@code http.server.requests}), before everything else, including Spring Security later: an
 * unauthenticated flood must be limited before it costs authentication work. Actuator endpoints are
 * never limited: a throttled health probe would make the platform restart a healthy instance.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RateLimitProperties.class)
class RateLimitConfig {

    static final int ORDER = Ordered.HIGHEST_PRECEDENCE + 10;

    private static final Logger log = LoggerFactory.getLogger(RateLimitConfig.class);

    @Bean
    @ConditionalOnProperty(prefix = "jobfinder.rate-limit", name = "enabled", havingValue = "true", matchIfMissing = true)
    FilterRegistrationBean<RateLimitFilter> rateLimitFilter(RateLimitProperties properties, JsonMapper json) {
        FilterRegistrationBean<RateLimitFilter> registration = new FilterRegistrationBean<>(new RateLimitFilter(properties, json));
        registration.addUrlPatterns("/api/*");
        registration.setOrder(ORDER);
        log.info("Rate limit: {} requests per {} s per client on /api/** (tracking at most {} clients)",
                properties.requests(), properties.period().toSeconds(), properties.maxClients());
        return registration;
    }
}
