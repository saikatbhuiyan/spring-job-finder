package com.shahabuddin.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

/*
Spring @Configuration annotation is part of the spring core framework.
Spring Configuration annotation indicates that the class has @Bean definition
methods. So Spring container can process the class and generate Spring Beans
to be used in the application.

@ComponentScan tells Spring which package(s) to search for classes annotated
with @Component (or @Service, @Repository, @Controller). It must point at the
package where those classes live: Vehicle is in com.shahabuddin.beans.
 */
@Configuration
@ComponentScan(basePackages = "com.shahabuddin.beans")
public class ProjectConfig {

    @Bean
    String hello() {
        return "Hello World";
    }

    @Bean
    Integer luckyNumber() {
        return 10;
    }
}
