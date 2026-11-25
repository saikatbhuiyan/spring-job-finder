package com.shahabuddin.jobfinder.shared.api;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;

/**
 * Problem Details written by a servlet filter, which answers without entering Spring MVC (so
 * {@link GlobalExceptionHandler} never sees the response): the same {@code instance} and
 * {@code traceId} as every other problem.
 */
final class ProblemResponses {

    private ProblemResponses() {
    }

    static void write(HttpServletRequest request, HttpServletResponse response, ProblemDetail problem, JsonMapper json)
            throws IOException {
        problem.setInstance(GlobalExceptionHandler.toInstance(request.getRequestURI()));
        TraceIdFilter.addTraceId(problem, request);
        response.setStatus(problem.getStatus());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        json.writeValue(response.getOutputStream(), problem);
    }
}
