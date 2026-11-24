package com.shahabuddin.jobfinder.shared.api;

import com.shahabuddin.jobfinder.shared.SortableBy;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.MethodParameter;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.List;

/**
 * Registers the {@link com.shahabuddin.jobfinder.shared.PageQuery} binding and checks every
 * {@link SortableBy} declaration when the application starts (docs/adr/0010-pagination-and-sorting.md).
 */
@Configuration(proxyBeanMethods = false)
class PaginationConfig implements WebMvcConfigurer {

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(new PageQueryArgumentResolver());
    }

    /** A wrong {@code @SortableBy} fails startup, not the first request that uses it. */
    @Bean
    static SmartInitializingSingleton sortableByDeclarationsCheck(ListableBeanFactory beans) {
        return () -> beans.getBeansOfType(RequestMappingHandlerMapping.class).values().forEach(mapping ->
                mapping.getHandlerMethods().values().forEach(handler -> {
                    for (MethodParameter parameter : handler.getMethodParameters()) {
                        SortableBy sortable = parameter.getParameterAnnotation(SortableBy.class);
                        if (sortable != null) {
                            PageQueryArgumentResolver.checkDeclaration(
                                    sortable, parameter.getParameterType(), handler.getShortLogMessage());
                        }
                    }
                }));
    }
}
