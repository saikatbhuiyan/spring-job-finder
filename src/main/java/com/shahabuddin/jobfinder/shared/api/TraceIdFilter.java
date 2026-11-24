package com.shahabuddin.jobfinder.shared.api;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.filter.ServerHttpObservationFilter;

import java.io.IOException;

/**
 * Request correlation (docs/adr/0013-request-correlation.md). Spring's observation filter, which
 * runs just before this one, starts a W3C trace for every request (or continues the caller's
 * {@code traceparent}), and Micrometer Tracing puts its {@code traceId} into the logging MDC, so
 * every log line of the request carries it. This filter makes the same ID visible outside the
 * server and keeps every failure of the request inside its trace:
 *
 * <ul>
 *   <li>{@value #HEADER} on every response, so a client can quote it in a bug report.</li>
 *   <li>The ID as a request attribute, read by every Problem Details writer
 *       ({@link #addTraceId}), including {@link ProblemDetailsErrorController}, which runs after the
 *       trace's scope has closed.</li>
 *   <li>An exception escaping a later filter (or the {@code DispatcherServlet}) is logged
 *       <b>here</b>, once, while the trace is in scope, and answered as a {@code 500}. Left to the
 *       servlet container, it was logged after the scope had closed: the stack trace that the
 *       client's trace ID should find was the one log line without it.</li>
 * </ul>
 */
final class TraceIdFilter extends OncePerRequestFilter {

    /** Response header with the request's trace ID (32 lowercase hex characters). */
    static final String HEADER = "X-Trace-Id";

    /** Problem Details extension member with the same ID. */
    static final String PROBLEM_PROPERTY = "traceId";

    private static final String ATTRIBUTE = TraceIdFilter.class.getName() + ".traceId";

    private static final Logger log = LoggerFactory.getLogger(TraceIdFilter.class);

    private final Tracer tracer;

    TraceIdFilter(Tracer tracer) {
        this.tracer = tracer;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Span span = tracer.currentSpan();
        if (span != null) {
            String traceId = span.context().traceId();
            request.setAttribute(ATTRIBUTE, traceId);
            response.setHeader(HEADER, traceId);
        }
        try {
            chain.doFilter(request, response);
        } catch (ServletException | RuntimeException ex) {
            // I/O failures (mostly clients that disconnected) keep the container's own handling.
            failed(request, response, ex);
        }
    }

    /** Same policy as {@link GlobalExceptionHandler} for a 5xx: logged once, nothing revealed. */
    private static void failed(HttpServletRequest request, HttpServletResponse response, Exception ex) throws IOException {
        // The exception no longer reaches the observation filter, so record it for metrics and traces.
        ServerHttpObservationFilter.recordHandledException(request, unwrap(ex));
        if (response.isCommitted()) {
            log.error("{} {} failed after the response was committed; the client received an incomplete response",
                    request.getMethod(), request.getRequestURI(), ex);
            return;
        }
        log.error("{} {} failed with {}", request.getMethod(), request.getRequestURI(),
                HttpStatus.INTERNAL_SERVER_ERROR.value(), ex);
        // Forwarded to ProblemDetailsErrorController: a generic 500 Problem Details.
        response.sendError(HttpStatus.INTERNAL_SERVER_ERROR.value());
    }

    /** The {@code DispatcherServlet} wraps failures in a {@code ServletException}; metrics name the cause. */
    private static Throwable unwrap(Exception ex) {
        return ex instanceof ServletException && ex.getCause() != null ? ex.getCause() : ex;
    }

    /** Adds {@code "traceId"} to a problem, if the request has a trace. */
    static void addTraceId(ProblemDetail problem, HttpServletRequest request) {
        String traceId = traceIdOf(request);
        if (traceId != null) {
            problem.setProperty(PROBLEM_PROPERTY, traceId);
        }
    }

    static @Nullable String traceIdOf(HttpServletRequest request) {
        return request.getAttribute(ATTRIBUTE) instanceof String traceId ? traceId : null;
    }
}
