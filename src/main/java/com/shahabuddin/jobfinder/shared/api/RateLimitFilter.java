package com.shahabuddin.jobfinder.shared.api;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

/**
 * Per-client token bucket for {@code /api/**} (see docs/adr/0012-rate-limiting.md). A request
 * without a token is answered here with {@code 429 Too Many Requests}, a {@code Retry-After} header
 * and Problem Details, without entering Spring MVC: rejecting must be the cheapest thing the
 * application does, since it happens most when the application is under pressure.
 *
 * <p>The client is identified by {@link HttpServletRequest#getRemoteAddr()}, never by a header the
 * client controls: {@code X-Forwarded-For} is only trusted once the servlet container is configured
 * to accept it from known proxies (see the ADR). Buckets live in a bounded cache: a client rotating
 * through addresses can't make the map grow without limit.
 */
final class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

    private final RateLimitProperties properties;
    private final JsonMapper json;
    private final Cache<String, Bucket> buckets;

    RateLimitFilter(RateLimitProperties properties, JsonMapper json) {
        this.properties = properties;
        this.json = json;
        // A bucket untouched for a whole period is full again, so dropping it changes nothing.
        this.buckets = Caffeine.newBuilder()
                .maximumSize(properties.maxClients())
                .expireAfterAccess(properties.period())
                .build();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String client = request.getRemoteAddr();
        ConsumptionProbe probe = buckets.get(client, key -> newBucket()).tryConsumeAndReturnRemaining(1);
        if (probe.isConsumed()) {
            chain.doFilter(request, response);
            return;
        }
        long retryAfterSeconds = Math.max(1, TimeUnit.NANOSECONDS.toSeconds(probe.getNanosToWaitForRefill() + 999_999_999));
        log.debug("Rate limit exceeded for {} on {} {}; retry after {} s", client, request.getMethod(), request.getRequestURI(), retryAfterSeconds);
        reject(request, response, retryAfterSeconds);
    }

    private Bucket newBucket() {
        return Bucket.builder()
                .addLimit(limit -> limit.capacity(properties.requests()).refillGreedy(properties.requests(), properties.period()))
                .build();
    }

    private void reject(HttpServletRequest request, HttpServletResponse response, long retryAfterSeconds) throws IOException {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS,
                "Too many requests. Retry after " + retryAfterSeconds + (retryAfterSeconds == 1 ? " second." : " seconds."));
        problem.setInstance(GlobalExceptionHandler.toInstance(request.getRequestURI()));
        TraceIdFilter.addTraceId(problem, request);
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(retryAfterSeconds));
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        json.writeValue(response.getOutputStream(), problem);
    }

    /** Number of clients currently tracked (for tests: the cache must stay bounded). */
    long trackedClients() {
        buckets.cleanUp();
        return buckets.estimatedSize();
    }
}
