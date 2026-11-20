# 00 — Project Setup: JDK, Maven, and pom.xml

## Goal

Understand the tools the project is built on before writing any Spring code:
which Java version runs it, how Maven downloads libraries, and what each file in the
project is for.

---

## 1. The JDK (Java Development Kit)

The **JDK** contains:

- `javac` — the **compiler**. It turns `.java` source files into `.class` bytecode.
- `java` — the **JVM launcher**. It runs `.class` bytecode.
- The **standard library** (`String`, `List`, `IO`, …).

Check your version:

```bash
java -version
# openjdk version "25.0.1" 2025-10-21
```

This project uses **Java 25**. Java releases a new version every 6 months; every few
versions is an **LTS** (Long-Term Support) release. Java 25 is an LTS (like 21, 17 and 11
before it), so it's a good choice for a project you want to keep.

> **Source code → bytecode → JVM.** Java is compiled once to bytecode, and the JVM runs that
> bytecode on any OS. That's the "write once, run anywhere" idea.

---

## 2. Maven: the build tool

Writing `javac` commands by hand gets painful once you have many files and external
libraries. **Maven** does this for you:

| Job | What Maven does |
|-----|-----------------|
| Dependency management | Downloads libraries (like Spring) and *their* dependencies |
| Build | Compiles `src/main/java` into `target/classes` |
| Standard layout | Every Maven project has the same folder structure |
| Lifecycle | Standard commands: `compile`, `test`, `package`, `install` |

Check your version:

```bash
mvn -v
# Apache Maven 3.9.12
```

### Where do downloaded libraries go?

To your **local repository**: `~/.m2/repository/`. The first build downloads from
**Maven Central** (the public library server). Later builds reuse the local copy.

For example, Spring Context ended up here:

```text
~/.m2/repository/org/springframework/spring-context/7.0.10/spring-context-7.0.10.jar
```

---

## 3. `pom.xml`, line by line

`pom.xml` stands for **Project Object Model**. It's the single file that describes the
project to Maven. See [pom.xml](../pom.xml).

```xml
<modelVersion>4.0.0</modelVersion>
```
The POM format version. It's always `4.0.0`; you never change it.

```xml
<groupId>com.shahabuddin</groupId>
<artifactId>job-finder</artifactId>
<version>1.0-SNAPSHOT</version>
```
These are the project's **coordinates**, its unique ID in the Maven world (often called "GAV"):

- **groupId**: who owns it, usually a reversed domain name (`com.shahabuddin`).
- **artifactId**: the project name (`job-finder`).
- **version**: `SNAPSHOT` means "work in progress, not a final release".

Every dependency you add is identified by the same three coordinates.

```xml
<properties>
    <maven.compiler.source>25</maven.compiler.source>
    <maven.compiler.target>25</maven.compiler.target>
    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
</properties>
```
- `source` / `target`: compile the code as Java 25 and produce Java 25 bytecode.
  (A modern alternative is a single `<maven.compiler.release>25</maven.compiler.release>`.)
- `sourceEncoding`: read source files as UTF-8, so characters like `’` in comments work
  the same on every machine.

```xml
<dependencies>
    <dependency>
        <groupId>org.springframework</groupId>
        <artifactId>spring-context</artifactId>
        <version>7.0.10</version>
    </dependency>
    <dependency>
        <groupId>jakarta.annotation</groupId>
        <artifactId>jakarta.annotation-api</artifactId>
        <version>3.0.0</version>
    </dependency>
</dependencies>
```

| Dependency | Why |
|------------|-----|
| `spring-context` | **Spring Framework 7**: the IoC container (notes 02–04) |
| `jakarta.annotation-api` | `@PostConstruct` / `@PreDestroy` (added 2026-11-20, see [note 04 §8](04-component-scan-and-bean-lifecycle.md)) |

### ✅ Mistake fixed (2026-11-20): `<dependency>` outside `<dependencies>`

The jakarta dependency was first pasted **after** the closing `</dependencies>` tag:

```xml
    </dependencies>
    <dependency>          ❌ not allowed here
        ...
    </dependency>
```

Maven refused to build anything:

```text
Malformed POM pom.xml: Unrecognised tag: 'dependency' ... line 24, column 17
```

**Lesson:** every `<dependency>` must sit **inside** the single `<dependencies>` block.
The error gives the line and column, so look there first. After editing `pom.xml`, also
click **"Load Maven Changes"** in the IDE so it picks up the new library.

### Transitive dependencies

We asked for `spring-context`, but Maven downloaded **nine** jars for it. That's because
`spring-context` itself depends on other libraries, and Maven pulls those in automatically.
These are called **transitive dependencies**.

```bash
mvn dependency:tree
```

The jars in this project:

| Jar | Why it's here |
|-----|---------------|
| `spring-context` | `ApplicationContext`, `@Configuration`, `@Bean` (what we use) |
| `spring-beans` | Bean definitions and the `BeanFactory`, the core of the container |
| `spring-core` | Low-level utilities used by all of Spring |
| `spring-aop` | Aspect-Oriented Programming (proxies); used internally |
| `spring-expression` | SpEL, the Spring Expression Language (`#{...}`) |
| `commons-logging` | Logging bridge |
| `jspecify` | Null-safety annotations (`@Nullable`) that Spring 7 uses |
| `micrometer-observation`, `micrometer-commons` | Metrics/observability hooks |

---

## 4. Standard Maven folder layout

```text
job-finder/
├── pom.xml                        ← project description for Maven
├── src/
│   ├── main/
│   │   ├── java/                  ← application source code
│   │   │   └── com/shahabuddin/   ← package folders match the package name
│   │   │       ├── Main.java
│   │   │       ├── beans/Vehicle.java
│   │   │       └── config/ProjectConfig.java
│   │   └── resources/             ← (later) config files like application.properties
│   └── test/java/                 ← (later) unit tests
├── target/                        ← build output (generated, never commit)
└── learning/                      ← these notes
```

**Rule:** a class in package `com.shahabuddin.beans` must live in the folder
`src/main/java/com/shahabuddin/beans/`. Java enforces this match.

---

## 5. `.gitignore`

[.gitignore](../.gitignore) tells Git which files **not** to track:

| Entry | Why ignored |
|-------|-------------|
| `target/` | Build output; anyone can regenerate it with `mvn compile` |
| `.idea/…`, `*.iml` | IntelliJ's personal settings |
| `.vscode/` | VS Code's personal settings |
| `.DS_Store` | macOS folder metadata |
| ~~`learning/`~~ | Was ignored at first; removed on 2026-11-19 so these notes are committed as part of the portfolio. |

---

## 6. How to run the project

### From the IDE
Open [Main.java](../src/main/java/com/shahabuddin/Main.java) and click the green ▶ next to
`main`.

### From the terminal (verified)

```bash
mvn -q compile exec:java -Dexec.mainClass=com.shahabuddin.Main
```

- `-q`: quiet; only show the program's output and errors.
- `compile`: build the code into `target/classes`.
- `exec:java`: run a Java class with all the dependencies on the classpath.
- `-Dexec.mainClass=…`: the fully-qualified name of the class with `main`.

Output:

```text
Hello and welcome!
Vehicle name from non-spring context is: Audi
Vehicle name from Spring Context is: Tesla
String value  from Spring Context is: Hello World
Integer value  from Spring Context is: 10
String value  from Spring Context is: Hello World
```

### Useful Maven commands

| Command | What it does |
|---------|--------------|
| `mvn compile` | Compile main code |
| `mvn test` | Compile and run tests |
| `mvn package` | Build a `.jar` in `target/` |
| `mvn clean` | Delete `target/` |
| `mvn dependency:tree` | Show all dependencies, including transitive ones |

---

## Common mistakes

| Symptom | Cause | Fix |
|---------|-------|-----|
| `release version 25 not supported` | Maven is running on an older JDK | `mvn -v` must show Java 25; set `JAVA_HOME` |
| `package org.springframework… does not exist` | Dependency missing or not downloaded | Check `pom.xml`, then reload Maven in the IDE |
| Class not found when running | Wrong package or folder | Folder path must match the `package` line |

## Interview questions

**Q: What is the difference between JDK, JRE and JVM?**
The JVM runs bytecode. The JRE is the JVM plus the standard library (enough to *run* Java).
The JDK is the JRE plus development tools like `javac` (needed to *build* Java).

**Q: What is a transitive dependency?**
A dependency of your dependency. Maven resolves these automatically.

**Q: What does SNAPSHOT mean?**
An in-development version that can change. Release versions (`1.0.0`) must never change.

**Q: What are Maven coordinates?**
`groupId:artifactId:version`, the unique ID of any Maven artifact.
