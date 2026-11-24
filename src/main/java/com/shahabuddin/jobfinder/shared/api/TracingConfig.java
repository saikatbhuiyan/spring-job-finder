package com.shahabuddin.jobfinder.shared.api;

import io.micrometer.tracing.Tracer;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.task.support.ContextPropagatingTaskDecorator;

/**
 * Request correlation (docs/adr/0013-request-correlation.md): the trace itself is Spring Boot's
 * (Micrometer Tracing with the OpenTelemetry bridge, W3C {@code traceparent} only; see
 * application.properties). This class adds what Boot doesn't do on its own.
 */
@Configuration(proxyBeanMethods = false)
class TracingConfig {

    /**
     * Right after Spring's observation filter ({@code HIGHEST_PRECEDENCE + 1}), which opens the
     * trace, and before everything else that can answer or fail a request: the rate limit's 429
     * ({@link RateLimitConfig#ORDER}), Spring Security later, Spring MVC.
     */
    static final int ORDER = Ordered.HIGHEST_PRECEDENCE + 2;

    @Bean
    FilterRegistrationBean<TraceIdFilter> traceIdFilter(Tracer tracer) {
        FilterRegistrationBean<TraceIdFilter> registration = new FilterRegistrationBean<>(new TraceIdFilter(tracer));
        registration.setOrder(ORDER);
        return registration;
    }

    /**
     * Carries the trace (and with it the MDC {@code traceId}) into tasks run on Boot's
     * {@code applicationTaskExecutor}: {@code @Async} methods and Spring Modulith's
     * {@code @ApplicationModuleListener}s. Without it their log lines had no trace ID. Boot applies a
     * {@code TaskDecorator} bean to the executors it auto-configures; an executor created by hand
     * needs it set explicitly.
     */
    @Bean
    ContextPropagatingTaskDecorator contextPropagatingTaskDecorator() {
        return new ContextPropagatingTaskDecorator();
    }
}
