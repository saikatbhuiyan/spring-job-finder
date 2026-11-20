package com.shahabuddin.jobfinder;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point of the Job Finder application.
 *
 * <p>{@code @SpringBootApplication} combines three annotations:
 * <ul>
 *   <li>{@code @SpringBootConfiguration}: this class is a {@code @Configuration} class;</li>
 *   <li>{@code @EnableAutoConfiguration}: Boot configures beans based on the classpath
 *       (e.g. Spring MVC + embedded Tomcat because spring-boot-starter-webmvc is present);</li>
 *   <li>{@code @ComponentScan}: scans this package ({@code com.shahabuddin.jobfinder})
 *       and all sub-packages for components.</li>
 * </ul>
 */
@SpringBootApplication
public class JobFinderApplication {

    public static void main(String[] args) {
        SpringApplication.run(JobFinderApplication.class, args);
    }
}
