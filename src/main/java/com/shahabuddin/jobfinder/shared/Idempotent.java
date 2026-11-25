package com.shahabuddin.jobfinder.shared;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a {@code POST} or {@code PATCH} endpoint whose clients must send an {@code Idempotency-Key}
 * header (see docs/adr/0014-idempotency-keys.md). Without one the request is a {@code 400}.
 *
 * <pre>{@code
 * @Idempotent
 * @PostMapping(version = "1")
 * ResponseEntity<JobResponse> create(@Valid @RequestBody CreateJobRequest request)
 * }</pre>
 *
 * Use it on every operation where executing a retry twice would do harm: creating a resource,
 * applying for a job, anything that sends a message or moves money. The first successful response
 * for a key is replayed to every retry with the same key and request, so a client that timed out can
 * safely send the request again. (A key is honoured on any {@code POST}/{@code PATCH} that carries
 * one; this annotation makes it mandatory.)
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Idempotent {
}
