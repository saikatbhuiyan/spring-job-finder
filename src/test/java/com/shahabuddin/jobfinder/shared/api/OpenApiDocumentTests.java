package com.shahabuddin.jobfinder.shared.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.client.RestTestClient;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code docs/openapi.yaml} is the API's published contract (docs/adr/0016-openapi-documentation.md):
 * the document clients and generators use, since a production instance doesn't serve its own. This
 * test generates it from the real application (no test endpoints) and fails when the committed file
 * differs, so every API change shows up as a diff of the contract in review.
 *
 * <p>After an intended change: {@code mvn test -Dtest=OpenApiDocumentTests -Dopenapi.update=true},
 * then review and commit the new file.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "springdoc.api-docs.enabled=true",
        "springdoc.cache.disabled=true"}) // per request: see OpenApiTests
@AutoConfigureRestTestClient
class OpenApiDocumentTests {

    private static final Path COMMITTED = Path.of("docs", "openapi.yaml");

    @Autowired
    private RestTestClient client;

    @Test
    void theCommittedDocumentMatchesTheCode() throws IOException {
        String generated = client.get().uri("/v3/api-docs.yaml").exchange()
                .expectStatus().isOk()
                .expectBody(String.class).returnResult().getResponseBody();

        if (Boolean.getBoolean("openapi.update")) {
            Files.writeString(COMMITTED, generated, StandardCharsets.UTF_8);
        }
        assertThat(COMMITTED).as("docs/openapi.yaml exists (create it with -Dopenapi.update=true)").exists();
        assertThat(Files.readString(COMMITTED, StandardCharsets.UTF_8))
                .as("docs/openapi.yaml is out of date: the API changed. Regenerate it with "
                        + "`mvn test -Dtest=OpenApiDocumentTests -Dopenapi.update=true`, review the diff, commit it")
                .isEqualTo(generated);
    }
}
