package com.shahabuddin.jobfinder.shared.api;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Unknown query parameters on {@code /api/**} are a {@code 400} (docs/adr/0011-filtering-and-search.md). */
@Configuration(proxyBeanMethods = false)
class QueryParametersConfig implements WebMvcConfigurer {

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new UnknownQueryParameterInterceptor()).addPathPatterns("/api/**");
    }
}
