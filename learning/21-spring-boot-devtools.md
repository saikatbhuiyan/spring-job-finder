# 21 — Spring Boot DevTools

## Goal

**DevTools** makes local development faster: change code, and the application restarts by itself
in a fraction of a second. It's also a **development-only** dependency, so the production question
is: *how do we guarantee it never reaches production?* This note covers both, verified on
Spring Boot 4.2.0, including several things that changed since Boot 3 and that most tutorials still
show the old way.

Status: ✅ **applied**. DevTools is active for `mvn spring-boot:run`, proven absent from the
packaged jar, and guarded by a test.

---

## 1. Adding it the production-safe way

[pom.xml](../pom.xml):

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-devtools</artifactId>
    <scope>runtime</scope>
    <optional>true</optional>
</dependency>
```

| Setting | Why |
|---------|-----|
| `runtime` scope | Our code never imports DevTools classes; it's only needed when running |
| `optional` | Not passed on to any project that depends on ours |
| *(nothing else needed)* | `spring-boot-maven-plugin` **excludes DevTools from the packaged jar by default** |

---

## 2. Three layers of protection (all verified)

```text
1) Build      spring-boot-maven-plugin leaves it out of the jar
                → `unzip -l target/job-finder-0.0.1-SNAPSHOT.jar | grep devtools` → 0 entries   ✅
2) Runtime    DevTools disables itself when the app runs as a "fully packaged" jar (java -jar)
                → java -jar: no devtools lines, main thread is "main" (not "restartedMain")      ✅
3) Tests      ProfileConfigurationTests launches the app WITHOUT DevTools and fails if it
              ever appears in a production-like launch (§6)                                      ✅
```

---

## 3. Automatic restart (verified)

### How it works: two class loaders

```text
┌─────────────────────────── JVM ───────────────────────────┐
│  base class loader     → libraries (Spring, Tomcat, …)     │  loaded once, kept
│  restart class loader  → YOUR classes (target/classes)     │  thrown away + recreated
└────────────────────────────────────────────────────────────┘
```

DevTools watches `target/classes`. When a compiled class changes, it discards only the **restart**
class loader and starts the application context again. The libraries stay loaded, which is why a
restart is much faster than a cold start.

### Verified with `mvn spring-boot:run`

```text
22:22:42.730  INFO [restartedMain  ] c.s.jobfinder.JobFinderApplication   : Starting JobFinderApplication ...
22:22:42.731  INFO [restartedMain  ] c.s.jobfinder.JobFinderApplication   : The following 1 profile is active: "dev"
22:22:42.758  INFO [restartedMain  ] evToolsPropertyDefaultsPostProcessor : Devtools property defaults active! Set 'spring.devtools.add-properties' to 'false' to disable
22:22:43.553  INFO [restartedMain  ] c.s.jobfinder.JobFinderApplication   : Started JobFinderApplication in 1.017 seconds

   → touch target/classes/.../JobFinderApplication.class   (simulates recompiling after an edit)

22:22:47.131  INFO [restartedMain  ] c.s.jobfinder.JobFinderApplication   : Started JobFinderApplication in 0.172 seconds
```

- The thread is named **`restartedMain`**, which is how you can tell DevTools is active.
- Cold start: **1.017 s**. Restart: **0.172 s** (about 6× faster). The gap grows as the app gets bigger.
- Health stayed `UP` after the restart.

### ⚠️ Restart is triggered by **compiled classes**, not by saving `.java` files

DevTools watches `target/classes`, so your IDE must **compile** on save:

| IDE | Setting |
|-----|---------|
| **IntelliJ IDEA** | Settings → Build, Execution, Deployment → Compiler → ✅ *Build project automatically*, and Settings → Advanced Settings → ✅ *Allow auto-make to start even if developed application is currently running*. Or just press **Build ▸ Build Project (⌘F9)** when you want a restart. |
| **VS Code** (Java extension) | Compiles automatically on save (`java.autobuild.enabled`, on by default) |
| Terminal only | Run `mvn compile` in a second terminal |

### What doesn't trigger a restart (Boot 4.2 defaults)

```text
spring.devtools.restart.exclude = META-INF/maven/**, META-INF/resources/**, resources/**,
    static/**, public/**, templates/**, **/*Test.class, **/*Tests.class, git.properties,
    META-INF/build-info.properties
```

Static files and templates are reloaded without a restart, and test classes are ignored.

### Useful restart settings

| Property (Boot 4.2 default) | Use |
|-----------------------------|-----|
| `spring.devtools.restart.enabled` (`true`) | Turn restart off but keep other DevTools features |
| `spring.devtools.restart.poll-interval` (`1s`) / `quiet-period` (`400ms`) | How often to check, and how long to wait for compiling to finish |
| `spring.devtools.restart.trigger-file` | Restart **only** when this file changes, e.g. `.reloadtrigger`. Useful when your IDE compiles on every keystroke |
| `spring.devtools.restart.additional-paths` | Watch extra folders |
| `spring.devtools.restart.log-condition-evaluation-delta` (`true`) | Log which auto-configurations changed after a restart |

---

## 4. Property defaults (changed in Boot 4)

DevTools switches on **development-friendly defaults**:

```text
Devtools property defaults active! Set 'spring.devtools.add-properties' to 'false' to disable
For additional web related logging consider setting the 'logging.level.web' property to 'DEBUG'
```

In **Boot 3** these defaults lived in one central list inside DevTools (template caching off, and so
on), and that's what most tutorials describe. In **Boot 4** each module contributes its own via a
`META-INF/spring-devtools.properties` file with a `defaults.` prefix. Searching every jar on our
runtime classpath found exactly one:

```properties
# spring-boot-webmvc-4.2.0.jar → META-INF/spring-devtools.properties
defaults.spring.mvc.log-resolved-exception=true
```

That logs exceptions that Spring MVC resolved into an error response, which is handy while
developing. More defaults appear automatically as modules are added (e.g. template engines).

---

## 5. LiveReload: off by default in Boot 4

```text
spring.devtools.livereload.enabled = false   (Boot 4.2 default; it was on by default in Boot 3)
spring.devtools.livereload.port    = 35729
```

LiveReload refreshes a browser tab when resources change (it needs a browser extension). Job Finder
is a **REST API** with no server-rendered pages, so it stays **off**.

---

## 6. The trap: DevTools activating inside a test (found and fixed)

`ProfileConfigurationTests` (note 20 §7) starts the application in a child JVM with
`java -cp <test classpath>` to check the `prod` profile. After adding DevTools, all tests still
passed. But launching the app exactly as the test does showed:

```text
"thread":{"name":"restartedMain"} ... "logger":"...devtools.env.DevToolsPropertyDefaultsPostProcessor" ...
```

**DevTools was active in the "production" test.** A plain `java -cp` launch counts as "not a
packaged jar", so DevTools switched itself on. The test was passing while checking a configuration
production never runs. A green test that checks the wrong thing is worse than no test.

**Fix:**

1. The child JVM gets the test classpath **minus `spring-boot-devtools`**, exactly what the packaged
   jar contains.
2. A **guard assertion**: the output must contain neither `devtools` nor `restartedMain`.

```java
private static String productionLikeClasspath() {
    return Arrays.stream(System.getProperty("java.class.path").split(File.pathSeparator))
            .filter(entry -> !entry.contains("spring-boot-devtools"))
            .collect(Collectors.joining(File.pathSeparator));
}
...
assertThat(lines).as("DevTools must not be active in a production-like launch")
        .noneSatisfy(l -> assertThat(l).containsAnyOf("devtools", "restartedMain"));
```

**Mutation check:** I removed the filter and both profile tests **failed** with
`DevTools must not be active in a production-like launch`. After restoring it, all 30 tests pass.

(Before writing the filter I checked that Surefire passes the **full** classpath in
`java.class.path`, 103 entries, rather than a single "manifest-only" jar. Otherwise filtering
wouldn't be possible.)

---

## 7. Remote DevTools: never in production

DevTools can also restart a **remote** application over HTTP
(`spring.devtools.remote.secret`, `RemoteSpringApplication`). That means the remote app accepts code
from the network, which is a serious security risk. Boot only enables it when you explicitly set a
secret **and** ship DevTools in the jar. Job Finder does neither, and §2 guarantees DevTools isn't
in the jar.

---

## 8. Personal (global) settings

Settings that are about **your** machine, not the project, go in
`~/.config/spring-boot/spring-boot-devtools.properties`, for example a trigger file. They apply to
every Spring Boot project you run locally and never end up in Git.

---

## Common mistakes

| Mistake | Result |
|---------|--------|
| DevTools with `compile` scope and not `optional` | Leaks to projects that depend on yours |
| Expecting a restart on save with no auto-compile in the IDE | Nothing happens (DevTools watches compiled classes) |
| Following Boot 3 tutorials for LiveReload | It's off by default in Boot 4 |
| Launching the app with `java -cp` in tests or scripts | DevTools activates; it's not production-like (§6) |
| Keeping state in static fields and expecting it to survive a restart | The restart class loader is new, so static state resets |
| Enabling remote DevTools in production | Remote code execution risk |

## Interview questions

**Q: What does Spring Boot DevTools provide?**
Automatic restart on classpath changes, development property defaults, optional LiveReload, and remote
restart support (development use only).

**Q: Why is a DevTools restart faster than a cold start?**
It uses two class loaders. Libraries stay in the base class loader; only the restart class loader with
your classes is recreated.

**Q: How do you make sure DevTools never runs in production?**
Declare it `runtime` + `optional`. `spring-boot-maven-plugin` excludes it from the packaged jar, and
DevTools disables itself when launched as a packaged jar. Verify both (jar contents, `java -jar`), and
make production-like tests run without it.

**Q: Why doesn't my app restart when I save a file?**
DevTools reacts to changed **compiled** classes. The IDE must build on save (or you trigger a build).

**Q: Which thread name tells you DevTools is active?**
`restartedMain`.
