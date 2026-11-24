package com.shahabuddin.jobfinder.shared.api;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * The per-client rate limit for {@code /api/**} (see docs/adr/0012-rate-limiting.md): a token
 * bucket holding {@code requests} tokens, refilled continuously so that it is full again after
 * {@code period}. A client can therefore send a burst of {@code requests}, then
 * {@code requests / period} on average.
 *
 * @param enabled    whether the limit applies at all
 * @param requests   bucket capacity: the burst size and the requests allowed per {@code period}
 * @param period     time to refill an empty bucket
 * @param maxClients clients whose buckets are kept in memory; beyond that the least recently seen are
 *                   dropped (they start again with a full bucket), so memory stays bounded
 */
@ConfigurationProperties("jobfinder.rate-limit")
record RateLimitProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("60") int requests,
        @DefaultValue("1m") Duration period,
        @DefaultValue("100000") int maxClients) {

    RateLimitProperties {
        if (requests < 1) {
            throw new IllegalArgumentException("jobfinder.rate-limit.requests must be at least 1, was " + requests);
        }
        if (period.compareTo(Duration.ofSeconds(1)) < 0) {
            throw new IllegalArgumentException("jobfinder.rate-limit.period must be at least 1s, was " + period);
        }
        if (maxClients < 1) {
            throw new IllegalArgumentException("jobfinder.rate-limit.max-clients must be at least 1, was " + maxClients);
        }
    }
}
