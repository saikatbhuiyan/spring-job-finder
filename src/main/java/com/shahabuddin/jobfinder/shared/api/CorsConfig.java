package com.shahabuddin.jobfinder.shared.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

import java.util.List;

/**
 * CORS for the REST API (see docs/adr/0007-cors-policy.md).
 *
 * <p>Applied as a servlet <b>filter</b>, not with Spring MVC's {@code CorsRegistry}: MVC-level CORS
 * only decorates responses produced by Spring MVC, so a {@code 401} sent by a filter (as Spring
 * Security does) would reach the browser without CORS headers, and the frontend couldn't read it.
 * The {@link CorsConfigurationSource} bean is named {@code corsConfigurationSource}, the name Spring
 * Security's {@code http.cors()} looks up, so it will be the single source of truth then too.
 *
 * <p>Only {@code /api/**} is CORS-enabled; Actuator endpoints aren't meant for browsers.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CorsProperties.class)
class CorsConfig {

    /** The HTTP methods the API uses. (OPTIONS for preflights is handled by the filter itself.) */
    static final List<String> ALLOWED_METHODS = List.of("GET", "POST", "PUT", "PATCH", "DELETE");

    /**
     * Request headers a browser may send. {@code new CorsConfiguration()} allows <b>none</b> by
     * default, so even {@code Content-Type: application/json} would fail its preflight.
     */
    static final List<String> ALLOWED_HEADERS = List.of("Content-Type", "Authorization", IdempotencyKey.HEADER);

    /**
     * Response headers the frontend may read. Browsers hide everything else: {@code Location} of a
     * {@code 201}, the API version retirement headers {@code Deprecation}, {@code Sunset} and
     * {@code Link} (ADR 0002), {@code Retry-After} of a {@code 429} (ADR 0012), the request's
     * {@code X-Trace-Id} (ADR 0013), and {@code Idempotent-Replayed} (ADR 0014).
     */
    static final List<String> EXPOSED_HEADERS = List.of("Location", "Deprecation", "Sunset", "Link", "Retry-After",
            TraceIdFilter.HEADER, IdempotencyFilter.REPLAYED_HEADER);

    private static final Logger log = LoggerFactory.getLogger(CorsConfig.class);

    @Bean
    CorsConfigurationSource corsConfigurationSource(CorsProperties properties) {
        CorsConfiguration api = new CorsConfiguration();
        api.setAllowedOrigins(properties.allowedOrigins());
        api.setAllowedMethods(ALLOWED_METHODS);
        api.setAllowedHeaders(ALLOWED_HEADERS);
        api.setExposedHeaders(EXPOSED_HEADERS);
        // The API authenticates with a bearer token in the Authorization header, not cookies, so
        // browsers don't need to send credentials. Turning this on would also forbid "*" origins
        // and require extra CSRF protection.
        api.setAllowCredentials(false);
        api.setMaxAge(properties.maxAge());

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", api);

        if (properties.allowedOrigins().isEmpty()) {
            log.info("CORS: no cross-origin browser access to /api/** (jobfinder.cors.allowed-origins is empty)");
        } else {
            log.info("CORS: allowing origins {} for /api/**", properties.allowedOrigins());
        }
        return source;
    }

    /** First filter in the chain, so every response (errors and future security rejections included) gets CORS headers. */
    @Bean
    FilterRegistrationBean<CorsFilter> corsFilter(CorsConfigurationSource corsConfigurationSource) {
        FilterRegistrationBean<CorsFilter> registration = new FilterRegistrationBean<>(new CorsFilter(corsConfigurationSource));
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }
}
