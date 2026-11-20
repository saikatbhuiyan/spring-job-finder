package com.shahabuddin.jobfinder;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

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
    void devWritesCompactLinesWithDebugAndShowsHealthDetails() throws Exception {
        try (RunningApp app = RunningApp.start("dev")) {
            // e.g. "22:15:03.123  INFO [main           ] c.s.jobfinder.JobFinderApplication   : Started ..."
            String compactLine = "\\d{2}:\\d{2}:\\d{2}\\.\\d{3} +[A-Z]{4,5} \\[.{15}] .{36} : .*";
            assertThat(app.lines()).anySatisfy(line -> assertThat(line).matches(compactLine).contains(STARTED));
            assertThat(app.lines()).anySatisfy(line ->
                    assertThat(line).matches(compactLine).contains("DEBUG").contains(DEBUG_STARTUP_LINE));
            assertThat(JSON.readTree(app.get("/actuator/health")).has("components")).isTrue();
        }
    }

    /** JobFinderApplication running in a child JVM with the given profile, on a random port. */
    private record RunningApp(Process process, List<String> lines, int port) implements AutoCloseable {

        static RunningApp start(String profile) throws IOException {
            Path java = Path.of(System.getProperty("java.home"), "bin", "java");
            Process process = new ProcessBuilder(
                    java.toString(),
                    "-cp", System.getProperty("java.class.path"),
                    JobFinderApplication.class.getName(),
                    "--spring.profiles.active=" + profile,
                    "--server.port=0",
                    "--spring.main.banner-mode=off",
                    "--spring.output.ansi.enabled=never")
                    .redirectErrorStream(true)
                    .start();

            List<String> lines = new ArrayList<>();
            int port = -1;
            Instant deadline = Instant.now().plus(Duration.ofSeconds(60));
            var reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
            String line;
            while (Instant.now().isBefore(deadline) && (line = reader.readLine()) != null) {
                if (!line.isBlank()) {
                    lines.add(line);
                }
                Matcher matcher = PORT.matcher(line);
                if (matcher.find()) {
                    port = Integer.parseInt(matcher.group(1));
                }
                if (line.contains(STARTED)) {
                    break;
                }
            }
            RunningApp app = new RunningApp(process, List.copyOf(lines), port);
            assertThat(lines).as("console output of profile '%s'", profile)
                    .anySatisfy(l -> assertThat(l).contains(STARTED));
            assertThat(port).as("HTTP port of profile '%s'", profile).isPositive();
            return app;
        }

        String get(String path) throws IOException, InterruptedException {
            HttpResponse<String> response = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200);
            return response.body();
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
