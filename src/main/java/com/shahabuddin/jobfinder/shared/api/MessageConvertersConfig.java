package com.shahabuddin.jobfinder.shared.api;

import org.springframework.context.annotation.Configuration;
import org.springframework.http.converter.HttpMessageConverters;
import org.springframework.http.converter.json.AbstractJackson2HttpMessageConverter;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Request and response bodies go through Jackson 3 only, the mapper the API's JSON rules are configured
 * on: unknown properties rejected (ADR 0005), no type coercion (ADR 0009), ISO-8601 dates (ADR 0011).
 *
 * <p>Spring MVC registers a converter for every Jackson 2 data format it finds on the classpath.
 * springdoc-openapi brings Jackson 2's YAML module (for its own {@code /v3/api-docs.yaml}, which doesn't
 * need the converter), and with it the API accepted YAML bodies with none of those rules ({@code
 * title: 123} became {@code "123"}, unknown properties were ignored: {@code 200}) and answered {@code
 * Accept: application/yaml} with dates as epoch numbers (measured; docs/adr/0016-openapi-documentation.md).
 * Without the converter that's a {@code 415}/{@code 406}, like any other unsupported media type.
 *
 * <p>Spring MVC configuration only (no beans), so {@code @WebMvcTest} slices get it too (ADR 0015).
 */
@Configuration(proxyBeanMethods = false)
class MessageConvertersConfig implements WebMvcConfigurer {

    @Override
    @SuppressWarnings("removal") // the Jackson 2 converters are deprecated for removal: that's the point
    public void configureMessageConverters(HttpMessageConverters.ServerBuilder builder) {
        builder.configureMessageConvertersList(converters ->
                converters.removeIf(AbstractJackson2HttpMessageConverter.class::isInstance));
    }
}
