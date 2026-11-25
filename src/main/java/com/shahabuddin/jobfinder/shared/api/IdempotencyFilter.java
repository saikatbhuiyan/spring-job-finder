package com.shahabuddin.jobfinder.shared.api;

import com.shahabuddin.jobfinder.shared.api.IdempotencyStore.Outcome;
import com.shahabuddin.jobfinder.shared.api.IdempotencyStore.Reservation;
import com.shahabuddin.jobfinder.shared.api.IdempotencyStore.StoredResponse;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Makes {@code POST} and {@code PATCH} requests with an {@code Idempotency-Key} safe to retry
 * (docs/adr/0014-idempotency-keys.md). Without it, a client whose request timed out can't know
 * whether it was executed: retrying created a second order every time (4 attempts, 4 orders,
 * measured).
 *
 * <ol>
 *   <li>The body is buffered (at most {@code max-request-size}) and the request fingerprinted:
 *       SHA-256 of method, path, query and body.</li>
 *   <li>The key is claimed atomically in the {@link IdempotencyStore}. The first request executes;
 *       a <b>2xx</b> response is stored and replayed (status, describing headers, body) to every
 *       retry, marked {@value #REPLAYED_HEADER}{@code : true}. Any other outcome frees the key: an
 *       error happened before or instead of the change (its transaction rolled back), so a retry must
 *       be able to succeed.</li>
 *   <li>A retry while the first is executing: {@code 409} with {@code Retry-After}. The key with a
 *       different request: {@code 422}.</li>
 * </ol>
 *
 * A request whose key is malformed passes untouched; {@link IdempotencyKeyInterceptor} rejects it
 * (and a missing key on an {@code @Idempotent} endpoint) with a {@code 400}. Keys are global until
 * requests are authenticated; then the store key gets the user as its scope (ADR).
 */
final class IdempotencyFilter extends OncePerRequestFilter {

    /** The methods the draft standard covers: the ones HTTP doesn't define as idempotent. */
    static final Set<String> METHODS = Set.of("POST", "PATCH");

    static final String REPLAYED_HEADER = "Idempotent-Replayed";

    /** Headers that describe the result and are replayed with it (not per-response ones like X-Trace-Id). */
    private static final List<String> REPLAYED_HEADERS = List.of(HttpHeaders.CONTENT_TYPE, HttpHeaders.LOCATION,
            HttpHeaders.ETAG, HttpHeaders.LAST_MODIFIED, HttpHeaders.CONTENT_LANGUAGE);

    private static final Logger log = LoggerFactory.getLogger(IdempotencyFilter.class);

    private final IdempotencyStore store;
    private final int maxRequestBytes;
    private final JsonMapper json;
    private final Map<String, Counter> outcomes;

    IdempotencyFilter(IdempotencyStore store, IdempotencyProperties properties, JsonMapper json, MeterRegistry meters) {
        this.store = store;
        this.maxRequestBytes = Math.toIntExact(properties.maxRequestSize().toBytes());
        this.json = json;
        this.outcomes = Map.of(
                "executed", counter(meters, "executed"),
                "replayed", counter(meters, "replayed"),
                "in_progress", counter(meters, "in_progress"),
                "key_reused", counter(meters, "key_reused"),
                "too_large", counter(meters, "too_large"));
    }

    private static Counter counter(MeterRegistry meters, String outcome) {
        return Counter.builder("jobfinder.idempotency.requests")
                .description("Requests with an Idempotency-Key, by what happened to them")
                .tag("outcome", outcome)
                .register(meters);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !METHODS.contains(request.getMethod()) || request.getHeader(IdempotencyKey.HEADER) == null;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        IdempotencyKey key = IdempotencyKey.of(request);
        if (key == null) {
            chain.doFilter(request, response); // malformed: IdempotencyKeyInterceptor answers 400
            return;
        }
        byte[] body = readBody(request);
        if (body == null) {
            count("too_large");
            ProblemResponses.write(request, response, ProblemDetail.forStatusAndDetail(HttpStatus.CONTENT_TOO_LARGE,
                    "Requests with an Idempotency-Key may have a body of at most " + maxRequestBytes + " bytes."), json);
            return;
        }
        switch (store.begin(key.value(), fingerprint(request, body))) {
            case Outcome.Started(Reservation reservation) -> execute(request, response, chain, body, reservation);
            case Outcome.Replay(StoredResponse stored) -> replay(request, response, stored);
            case Outcome.InProgress() -> {
                count("in_progress");
                response.setHeader(HttpHeaders.RETRY_AFTER, "1");
                ProblemResponses.write(request, response, problem(HttpStatus.CONFLICT, ProblemTypes.REQUEST_IN_PROGRESS,
                        "Request in progress",
                        "A request with this Idempotency-Key is still being processed. Retry later to get its result."), json);
            }
            case Outcome.KeyReused() -> {
                count("key_reused");
                ProblemResponses.write(request, response, problem(HttpStatus.UNPROCESSABLE_CONTENT,
                        ProblemTypes.IDEMPOTENCY_KEY_REUSED, "Idempotency key reused",
                        "This Idempotency-Key was already used for a different request. Use a new key for a new request."), json);
            }
        }
    }

    private void execute(HttpServletRequest request, HttpServletResponse response, FilterChain chain, byte[] body,
                         Reservation reservation) throws ServletException, IOException {
        count("executed");
        ContentCachingResponseWrapper captured = new ContentCachingResponseWrapper(response);
        try {
            chain.doFilter(new BufferedBodyRequest(request, body), captured);
        } catch (Throwable failure) {
            store.release(reservation);
            throw failure;
        }
        if (HttpStatus.valueOf(captured.getStatus()).is2xxSuccessful() && !request.isAsyncStarted()) {
            store.complete(reservation, snapshot(captured));
        } else {
            store.release(reservation);
        }
        captured.copyBodyToResponse();
    }

    private void replay(HttpServletRequest request, HttpServletResponse response, StoredResponse stored) throws IOException {
        count("replayed");
        log.debug("Replaying the stored {} for {} {}", stored.status(), request.getMethod(), request.getRequestURI());
        response.setStatus(stored.status());
        stored.headers().forEach(response::setHeader);
        response.setHeader(REPLAYED_HEADER, "true");
        response.setContentLength(stored.body().length);
        response.getOutputStream().write(stored.body());
    }

    private static StoredResponse snapshot(ContentCachingResponseWrapper response) {
        Map<String, String> headers = new LinkedHashMap<>();
        for (String name : REPLAYED_HEADERS) {
            String value = HttpHeaders.CONTENT_TYPE.equals(name) ? response.getContentType() : response.getHeader(name);
            if (value != null) {
                headers.put(name, value);
            }
        }
        return new StoredResponse(response.getStatus(), headers, response.getContentAsByteArray());
    }

    /** The body, or {@code null} if it is larger than allowed. */
    private byte[] readBody(HttpServletRequest request) throws IOException {
        if (request.getContentLengthLong() > maxRequestBytes) {
            return null;
        }
        byte[] body = request.getInputStream().readNBytes(maxRequestBytes + 1);
        return body.length > maxRequestBytes ? null : body;
    }

    /**
     * What makes two requests "the same": method, path, query and body, byte for byte. A retry resends
     * the same bytes; headers (including the trace) legitimately differ between attempts.
     */
    private static String fingerprint(HttpServletRequest request, byte[] body) {
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            String query = request.getQueryString();
            String target = request.getMethod() + " " + request.getRequestURI() + (query != null ? "?" + query : "") + "\n";
            sha256.update(target.getBytes(StandardCharsets.UTF_8));
            sha256.update(body);
            return HexFormat.of().formatHex(sha256.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("Every JVM provides SHA-256", impossible);
        }
    }

    private static ProblemDetail problem(HttpStatus status, URI type, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(type);
        problem.setTitle(title);
        return problem;
    }

    private void count(String outcome) {
        outcomes.get(outcome).increment();
    }

    /** The request with its already-read body, readable again by Spring MVC. */
    private static final class BufferedBodyRequest extends HttpServletRequestWrapper {

        private final byte[] body;

        BufferedBodyRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream in = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override
                public int read() {
                    return in.read();
                }

                @Override
                public int read(byte[] buffer, int offset, int length) {
                    return in.read(buffer, offset, length);
                }

                @Override
                public boolean isFinished() {
                    return in.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener listener) {
                    throw new UnsupportedOperationException("Non-blocking reads are not supported");
                }
            };
        }

        @Override
        public BufferedReader getReader() {
            String encoding = getCharacterEncoding();
            Charset charset = encoding != null ? Charset.forName(encoding) : StandardCharsets.UTF_8;
            return new BufferedReader(new InputStreamReader(getInputStream(), charset));
        }

        @Override
        public int getContentLength() {
            return body.length;
        }

        @Override
        public long getContentLengthLong() {
            return body.length;
        }
    }
}
