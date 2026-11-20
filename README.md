# Spring Job Finder

A job-search backend built with **Java 25** and **Spring**, developed step by step as a
learning project. Every concept used in the code, and many studied alongside it, is
documented and verified in the [`learning/`](learning/README.md) notes.

> **Status:** early stage. The project is currently at the **Spring Core** stage (IoC
> container, beans, dependency injection, scopes, lifecycle). The Job Finder REST API is
> built on top of this foundation in the next phases; see the [roadmap](learning/roadmap.md).

## Tech stack

| | Current | Planned |
|-|---------|---------|
| Language | Java 25 | |
| Framework | Spring Framework 7 (`spring-context`) | Spring Boot 4, Spring Web MVC, Spring Data JPA, Spring Security |
| Build | Maven | |
| Database | | PostgreSQL + Flyway |
| Testing | | JUnit 5, Testcontainers |
| Ops | | Docker, GitHub Actions |

## Run it

Requirements: JDK 25 and Maven 3.9+.

```bash
mvn -q compile exec:java -Dexec.mainClass=com.shahabuddin.Main
```

Current output (a `@Component` bean with init/destroy lifecycle callbacks):

```text
Vehicle bean initialized.
Vehicle name from Spring Context is: null
Vehicle bean destroyed.
```

## Project structure

```text
src/main/java/com/shahabuddin/
├── Main.java                 # starts the Spring ApplicationContext
├── beans/Vehicle.java        # @Component with InitializingBean / DisposableBean
└── config/ProjectConfig.java # @Configuration + @ComponentScan
learning/                     # step-by-step notes (see below)
```

## Learning notes

The [`learning/`](learning/README.md) folder is a 15-part, step-by-step journal. Each note
explains a concept from scratch, shows the code, includes **real output from running
experiments** on Spring 7, and ends with common mistakes and interview questions.

Topics so far: project setup, Java essentials, the IoC container (`BeanFactory` vs
`ApplicationContext`), bean naming, `@Primary` / `@Qualifier`, component scanning and
stereotypes, the bean lifecycle, `@Import`, backend layering, dependency injection and
autowiring, circular dependencies, programmatic bean registration (Spring 7
`BeanRegistrar`), all six bean scopes, and eager vs lazy initialization.

## Roadmap

1. **Spring Core** (in progress): configuration, DI, scopes, lifecycle, AOP, testing
2. **Spring Boot**: auto-configuration, profiles, logging
3. **Job Finder API**: REST endpoints for jobs, companies and applications, JPA +
   PostgreSQL, search and pagination, JWT security, OpenAPI docs, Docker, CI

Details: [learning/roadmap.md](learning/roadmap.md).
