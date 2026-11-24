package com.shahabuddin.jobfinder;

import org.awaitility.core.ConditionTimeoutException;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Locks in the per-environment (profile) contract by starting the real application in a
 * <b>separate JVM</b> per profile, reading its console output and calling it over HTTP.
 *
 * <p>Why not {@code @SpringBootTest}: Spring Boot initializes the logging format (pattern or
 * structured JSON) once per JVM, so in a shared test JVM a later context keeps the format of the
 * first one, while log levels are re-applied per context. Production runs one application per JVM,
 * which is exactly what this test reproduces.
 */
class ProfileConfigurationTests {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String STARTED = "Started JobFinderApplication";
    private static final String DEBUG_STARTUP_LINE = "Running with Spring Boot";
    private static final Pattern PORT = Pattern.compile("Tomcat started on port (\\d+)");
    private static final String RATE_LIMIT_OF_ONE = "--jobfinder.rate-limit.requests=1";
    private static final String RATE_LIMITED = "Rate limit exceeded for";

    @Test
    void prodWritesEcsJsonHidesHealthDetailsAndHasNoDebug() throws Exception {
        try (RunningApp app = RunningApp.start("prod")) {
            assertThat(app.lines()).allSatisfy(line -> {
                JsonNode json = JSON.readTree(line);          // every line is one JSON object
                assertThat(json.path("service").path("name").asString()).isEqualTo("job-finder");
                assertThat(json.path("service").path("environment").asString()).isEqualTo("prod");
                assertThat(json.has("@timestamp")).isTrue();
            });
            assertThat(app.lines()).noneSatisfy(line -> assertThat(line).contains(DEBUG_STARTUP_LINE));
            assertThat(JSON.readTree(app.get("/actuator/health")).has("components")).isFalse();
        }
    }

    @Test
    void prodLogLinesOfARequestCarryItsTraceIdUnderTheEcsName() throws Exception {
        // A request that logs: the rate limit's (DEBUG) rejection, with a limit of 1 request.
        try (RunningApp app = RunningApp.start("prod", RATE_LIMIT_OF_ONE,
                "--logging.level.com.shahabuddin.jobfinder.shared.api.RateLimitFilter=debug")) {
            String traceId = app.rejectedTraceId();
            JsonNode rejection = JSON.readTree(app.awaitLine(line -> line.contains(RATE_LIMITED)));
            assertThat(rejection.path("trace.id").asString()).isEqualTo(traceId);
            assertThat(rejection.path("span.id").asString()).matches("[0-9a-f]{16}");
            assertThat(rejection.has("traceId")).as("the MDC key is renamed, not duplicated").isFalse();
        }
    }

    @Test
    void devWritesCompactLinesWithDebugAndShowsHealthDetails() throws Exception {
        try (RunningApp app = RunningApp.start("dev")) {
            // e.g. "22:15:03.123  INFO [main           ] c.s.jobfinder.JobFinderApplication   : Started ..."
            // (32 blank characters where a request's trace ID goes)
            String compactLine = "\\d{2}:\\d{2}:\\d{2}\\.\\d{3} +[A-Z]{4,5} \\[.{15}] .{32} .{36} : .*";
            assertThat(app.lines()).anySatisfy(line -> assertThat(line).matches(compactLine).contains(STARTED));
            assertThat(app.lines()).anySatisfy(line ->
                    assertThat(line).matches(compactLine).contains("DEBUG").contains(DEBUG_STARTUP_LINE));
            assertThat(JSON.readTree(app.get("/actuator/health")).has("components")).isTrue();
        }
    }

    @Test
    void devLogLinesOfARequestShowItsTraceId() throws Exception {
        try (RunningApp app = RunningApp.start("dev", RATE_LIMIT_OF_ONE)) {
            String traceId = app.rejectedTraceId();
            assertThat(app.awaitLine(line -> line.contains(RATE_LIMITED))).contains(" " + traceId + " ");
        }
    }

    /**
     * JobFinderApplication running in a child JVM with the given profile, on a random port. Its console
     * output is read on a background thread for the whole run, so lines logged while handling requests
     * can be awaited too (and the child never blocks on a full output pipe).
     */
    private record RunningApp(Process process, List<String> output, int port) implements AutoCloseable {

        static RunningApp start(String profile, String... extraArgs) throws IOException, InterruptedException {
            Path java = Path.of(System.getProperty("java.home"), "bin", "java");
            List<String> command = new ArrayList<>(List.of(
                    java.toString(),
                    "-cp", productionLikeClasspath(),
                    JobFinderApplication.class.getName(),
                    "--spring.profiles.active=" + profile,
                    "--server.port=0",
                    "--spring.main.banner-mode=off",
                    "--spring.output.ansi.enabled=never"));
            command.addAll(List.of(extraArgs));
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();

            List<String> output = new CopyOnWriteArrayList<>();
            Thread reader = new Thread(() -> {
                try (var in = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    in.lines().filter(line -> !line.isBlank()).forEach(output::add);
                } catch (IOException | UncheckedIOException closed) {
                    // The child exited.
                }
            }, "child-output-" + profile);
            reader.setDaemon(true);
            reader.start();

            RunningApp starting = new RunningApp(process, output, -1);
            try {
                starting.awaitLine(line -> line.contains(STARTED));
            } catch (ConditionTimeoutException notStarted) {
                starting.close();
                throw new AssertionError("console output of profile '" + profile + "':\n" + String.join("\n", output), notStarted);
            }
            int port = output.stream().map(PORT::matcher).filter(Matcher::find)
                    .mapToInt(matcher -> Integer.parseInt(matcher.group(1))).findFirst().orElse(-1);
            RunningApp app = new RunningApp(process, output, port);
            assertThat(port).as("HTTP port of profile '%s'", profile).isPositive();
            assertThat(app.lines()).as("DevTools must not be active in a production-like launch")
                    .noneSatisfy(l -> assertThat(l).containsAnyOf("devtools", "restartedMain"));
            return app;
        }

        /** Everything logged so far. */
        List<String> lines() {
            return List.copyOf(output);
        }

        String awaitLine(Predicate<String> condition) {
            return await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(50))
                    .until(() -> output.stream().filter(condition).findFirst(), Optional::isPresent)
                    .orElseThrow();
        }
        /**
         * The test classpath minus spring-boot-devtools. The packaged jar never contains DevTools
         * (spring-boot-maven-plugin excludes it), and a plain {@code java -cp} launch would otherwise
         * activate it (restart classloader, dev property defaults), so the child must not see it.
         */
        private static String productionLikeClasspath() {
            return Arrays.stream(System.getProperty("java.class.path").split(File.pathSeparator))
                    .filter(entry -> !entry.contains("spring-boot-devtools"))
                    .collect(Collectors.joining(File.pathSeparator));
        }

        String get(String path) throws IOException, InterruptedException {
            HttpResponse<String> response = send(path);
            assertThat(response.statusCode()).isEqualTo(200);
            return response.body();
        }

        /**
         * With {@link #RATE_LIMIT_OF_ONE}: the second API request is rejected (and logged at DEBUG by
         * the rate limit); returns that request's trace ID, from the response header.
         */
        String rejectedTraceId() throws IOException, InterruptedException {
            send("/api/v1/profile-probe");
            HttpResponse<String> rejected = send("/api/v1/profile-probe");
            assertThat(rejected.statusCode()).isEqualTo(429);
            return rejected.headers().firstValue("X-Trace-Id").orElseThrow();
        }

        private HttpResponse<String> send(String path) throws IOException, InterruptedException {
            try (HttpClient http = HttpClient.newHttpClient()) {
                return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).build(),
                        HttpResponse.BodyHandlers.ofString());
            }
        }

        @Override
        public void close() throws InterruptedException {
            process.destroy();
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
        }
    }
}
