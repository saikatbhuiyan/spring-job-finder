package com.shahabuddin.jobfinder.shared.api;

import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.cfg.CoercionAction;
import tools.jackson.databind.cfg.CoercionInputShape;
import tools.jackson.databind.type.LogicalType;

/**
 * Request bodies must use the JSON types the API documents (see docs/adr/0009-request-validation.md).
 *
 * <p>Jackson converts between scalar types by default: {@code "5000"} becomes the number 5000,
 * {@code 2.7} becomes the integer {@code 2} (silently truncated), {@code ""} becomes {@code null}, and
 * {@code 123} or {@code true} becomes a string. Number-related coercion is switched off in
 * application.properties ({@code spring.jackson.mapper.allow-coercion-of-scalars},
 * {@code spring.jackson.deserialization.accept-float-as-int}); Boot has no property for coercion
 * <b>into</b> strings, so that part is configured here. A wrong type is then a 400
 * {@code validation-error} with code {@code InvalidType}.
 */
@Configuration(proxyBeanMethods = false)
class JsonBindingConfig {

    @Bean
    JsonMapperBuilderCustomizer rejectScalarsForStringProperties() {
        return builder -> builder.withCoercionConfig(LogicalType.Textual, config -> config
                .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail));
    }
}
