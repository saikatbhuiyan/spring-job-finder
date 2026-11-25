package com.shahabuddin.jobfinder.shared.api;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;

import java.time.Duration;

/**
 * Idempotency keys (see docs/adr/0014-idempotency-keys.md).
 *
 * @param keyTtl         how long a completed key and its response are kept: a retry within this time
 *                       is answered with the stored response; later, the key is new again. Published
 *                       in docs/problems.md, as the draft standard asks
 * @param maxRequestSize the largest body of a request with a key: the body is buffered to fingerprint
 *                       it, so it must be bounded (larger: {@code 413})
 * @param maxStoredSize  memory for stored responses; beyond it the least recently used are dropped
 */
@ConfigurationProperties("jobfinder.idempotency")
record IdempotencyProperties(
        @DefaultValue("24h") Duration keyTtl,
        @DefaultValue("1MB") DataSize maxRequestSize,
        @DefaultValue("64MB") DataSize maxStoredSize) {

    IdempotencyProperties {
        if (keyTtl.compareTo(Duration.ofMinutes(1)) < 0) {
            throw new IllegalArgumentException("jobfinder.idempotency.key-ttl must be at least 1m, was " + keyTtl);
        }
        if (maxRequestSize.toBytes() < DataSize.ofKilobytes(1).toBytes()
                || maxRequestSize.toBytes() > DataSize.ofMegabytes(64).toBytes()) {
            throw new IllegalArgumentException("jobfinder.idempotency.max-request-size must be between 1KB and 64MB, was "
                    + maxRequestSize);
        }
        if (maxStoredSize.toBytes() < DataSize.ofMegabytes(1).toBytes()) {
            throw new IllegalArgumentException("jobfinder.idempotency.max-stored-size must be at least 1MB, was "
                    + maxStoredSize);
        }
    }
}
