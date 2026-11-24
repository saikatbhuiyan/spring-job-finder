package com.shahabuddin.jobfinder.shared.api;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.webmvc.error.ErrorController;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Problem Details for errors that never reach a controller: an exception in a servlet filter,
 * {@code response.sendError(401)} (as Spring Security does), or an error raised by the servlet
 * container. The container forwards those to the error path ({@code /error}). This replaces Spring
 * Boot's {@code BasicErrorController}, which answers with its legacy
 * {@code {"timestamp","status","error","path"}} body (it backs off when an {@link ErrorController}
 * bean exists).
 *
 * <p>Like {@link GlobalExceptionHandler}, a 5xx never reveals the exception message.
 */
@RestController
class ProblemDetailsErrorController implements ErrorController {

    private static final Logger log = LoggerFactory.getLogger(ProblemDetailsErrorController.class);

    /** Every HTTP method on purpose: a failed POST is forwarded here as a POST. */
    @RequestMapping("${spring.web.error.path:${error.path:/error}}")
    ResponseEntity<ProblemDetail> error(HttpServletRequest request) {
        HttpStatus status = statusOf(request);
        ProblemDetail problem = ProblemDetail.forStatus(status);
        if (status.is5xxServerError()) {
            problem.setDetail(GlobalExceptionHandler.UNEXPECTED_ERROR_DETAIL);
        }
        String requestUri = (String) request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI);
        if (requestUri != null) {
            problem.setInstance(GlobalExceptionHandler.toInstance(requestUri));
        }
        // Set during the original dispatch: this one runs after the trace's scope has closed.
        TraceIdFilter.addTraceId(problem, request);
        // Not logged at ERROR here: an exception was already logged with its stack trace before the
        // forward to this path, by TraceIdFilter (inside the trace) or, for a failure in a filter
        // that runs before it, by the servlet container. A second copy would duplicate every alert.
        log.debug("Error {} on {} {} answered with Problem Details", status.value(), request.getMethod(), requestUri);
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(problem);
    }

    private static HttpStatus statusOf(HttpServletRequest request) {
        if (request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE) instanceof Integer code) {
            HttpStatus status = HttpStatus.resolve(code);
            return status != null ? status : HttpStatus.INTERNAL_SERVER_ERROR;
        }
        // No error is being forwarded: someone requested the error path directly.
        return HttpStatus.NOT_FOUND;
    }
}
