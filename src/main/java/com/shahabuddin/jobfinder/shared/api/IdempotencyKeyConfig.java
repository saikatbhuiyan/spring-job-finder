package com.shahabuddin.jobfinder.shared.api;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * The {@code 400}s for a missing or malformed {@code Idempotency-Key} (docs/adr/0014-idempotency-keys.md):
 * only Spring MVC knows the handler and its {@code @Idempotent} annotation. Spring MVC configuration
 * only, so {@code @WebMvcTest} slices get the same contract as the application without the filter and
 * its dependencies ({@link IdempotencyConfig}).
 */
@Configuration(proxyBeanMethods = false)
class IdempotencyKeyConfig implements WebMvcConfigurer {

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new IdempotencyKeyInterceptor()).addPathPatterns("/api/**");
    }
}
