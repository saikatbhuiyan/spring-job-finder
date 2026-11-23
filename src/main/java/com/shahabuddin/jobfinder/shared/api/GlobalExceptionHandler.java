package com.shahabuddin.jobfinder.shared.api;

import com.shahabuddin.jobfinder.shared.ConflictException;
import com.shahabuddin.jobfinder.shared.DomainException;
import com.shahabuddin.jobfinder.shared.ResourceNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.converter.HttpMessageNotWritableException;
import org.springframework.validation.method.ParameterErrors;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.MatrixVariable;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.filter.ServerHttpObservationFilter;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.exc.MismatchedInputException;
import tools.jackson.databind.exc.UnrecognizedPropertyException;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

/**
 * Turns every exception thrown while handling a request into an RFC 9457 Problem Details response
 * (see docs/adr/0006-global-exception-handling.md and the catalogue in docs/problems.md).
 *
 * <ul>
 *   <li>Spring MVC's own exceptions (404 unknown path, 405, 415, invalid API version...) keep the
 *       standard handling inherited from {@link ResponseEntityExceptionHandler}.</li>
 *   <li>Validation and unreadable bodies are refined into a {@code validation-error} problem that says
 *       <b>which</b> field or parameter is wrong.</li>
 *   <li>{@link DomainException}s thrown by use cases map to 404 / 409 with their (client-safe)
 *       message.</li>
 *   <li>Anything else is a bug or an infrastructure failure. <b>Every</b> 5xx, including those of
 *       the inherited handling, is logged once with its stack trace and answered with a generic
 *       body that never reveals the cause ({@link #handleExceptionInternal}).</li>
 *   <li>A client that disconnected while the response was being written is not an error.</li>
 * </ul>
 *
 * Extending {@code ResponseEntityExceptionHandler} also replaces Spring Boot's own
 * {@code ProblemDetailsExceptionHandler} (it backs off when such a bean exists), so there is exactly
 * one handler. It must stay the <b>only</b> {@code @ControllerAdvice}: Spring asks advice beans in
 * order and uses the first one with any matching handler, so this class's {@code Exception}
 * catch-all would shadow a module's own advice (enforced by {@code ArchitectureRules}). Modules
 * report business failures as {@link DomainException}s instead.
 *
 * <p>Errors raised outside Spring MVC (filters, the servlet container) never reach this class;
 * {@link ProblemDetailsErrorController} covers those.
 */
@RestControllerAdvice
class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    static final String UNEXPECTED_ERROR_DETAIL = "An unexpected error occurred.";

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    // --- Business failures reported by use cases ----------------------------------------------

    @ExceptionHandler(DomainException.class)
    ResponseEntity<Object> handleDomainException(DomainException ex, WebRequest request) {
        // Exhaustive over the sealed hierarchy: a new DomainException subtype won't compile until
        // its HTTP mapping is decided here.
        ProblemDetail problem = switch (ex) {
            case ResourceNotFoundException e ->
                    problem(HttpStatus.NOT_FOUND, ProblemTypes.RESOURCE_NOT_FOUND, "Resource not found", e.getMessage());
            case ConflictException e ->
                    problem(HttpStatus.CONFLICT, ProblemTypes.CONFLICT, "Conflict", e.getMessage());
        };
        log.debug("{} answered with {}: {}", ex.getClass().getSimpleName(), problem.getStatus(), ex.getMessage());
        return handleExceptionInternal(ex, problem, new HttpHeaders(), HttpStatusCode.valueOf(problem.getStatus()), request);
    }

    // --- Everything else: bugs and infrastructure failures ------------------------------------

    @ExceptionHandler(Exception.class)
    @Nullable ResponseEntity<Object> handleUnexpected(Exception ex, WebRequest request) {
        return handleExceptionInternal(ex, null, new HttpHeaders(), HttpStatus.INTERNAL_SERVER_ERROR, request);
    }

    /**
     * The single exit of this class: every handler, our own and the inherited Spring MVC ones,
     * ends here. That makes it the one place to enforce the 5xx policy: some inherited handlers
     * (an unwritable response, a misnamed {@code @PathVariable}...) answer 500 without logging
     * anything and with a detail that names internals.
     */
    @Override
    protected @Nullable ResponseEntity<Object> handleExceptionInternal(
            Exception ex, @Nullable Object body, HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {
        if (statusCode.is5xxServerError()) {
            return serverError(ex, headers, statusCode, request);
        }
        return super.handleExceptionInternal(ex, body, headers, statusCode, request);
    }

    /**
     * A 5xx is our fault: log it once, with the stack trace, and tell the client nothing about the
     * cause. If the response was already committed (a failure halfway through writing a large
     * body), no error response can be sent any more; the client gets an incomplete body.
     */
    private @Nullable ResponseEntity<Object> serverError(
            Exception ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        HttpServletRequest servletRequest = ((ServletWebRequest) request).getRequest();
        HttpServletResponse servletResponse = ((ServletWebRequest) request).getResponse();
        if (servletResponse != null && servletResponse.isCommitted()) {
            log.error("{} {} failed after the response was committed; the client received an incomplete response",
                    servletRequest.getMethod(), servletRequest.getRequestURI(), ex);
            // The status on the wire stays 200, and Spring only records handled exceptions for a
            // 5xx status. Record it ourselves, or metrics and traces would show a success.
            ServerHttpObservationFilter.recordHandledException(servletRequest, ex);
            return null;
        }
        log.error("{} {} failed with {}", servletRequest.getMethod(), servletRequest.getRequestURI(), status.value(), ex);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, UNEXPECTED_ERROR_DETAIL);
        return super.handleExceptionInternal(ex, problem, headers, status, request);
    }

    /**
     * Writing the response failed. When that is because the client disconnected (closed the tab,
     * lost the network), it is routine, not a server error: hand it to Spring's own handling for
     * disconnected clients (one DEBUG line, logger {@code ...DisconnectedClient}, nothing sent).
     *
     * <p>Detected only by Spring's {@link AsyncRequestNotUsableException}, raised by its response
     * wrapper when the client connection is unusable. Spring's broader
     * {@code DisconnectedClientHelper} is deliberately not used for this: it treats any
     * {@code EOFException} in the cause chain as a disconnect, so a real failure could be hidden.
     */
    @Override
    protected @Nullable ResponseEntity<Object> handleHttpMessageNotWritable(
            HttpMessageNotWritableException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        AsyncRequestNotUsableException clientGone = findCause(ex, AsyncRequestNotUsableException.class);
        if (clientGone != null) {
            return handleAsyncRequestNotUsableException(clientGone, request);
        }
        return super.handleHttpMessageNotWritable(ex, headers, status, request);
    }

    /**
     * Every response from this class passes through here (also the ones produced by the inherited
     * Spring MVC handling): set {@code instance} to the request path, so a client can report
     * exactly which call failed. The query string is left out, as it may carry personal data.
     */
    @Override
    protected ResponseEntity<Object> createResponseEntity(
            @Nullable Object body, HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {
        if (body instanceof ProblemDetail problem && problem.getInstance() == null
                && request instanceof ServletWebRequest servletWebRequest) {
            problem.setInstance(toInstance(servletWebRequest.getRequest().getRequestURI()));
        }
        return super.createResponseEntity(body, headers, statusCode, request);
    }

    // --- Validation: say which field or parameter is wrong ------------------------------------

    /** {@code @Valid @RequestBody} failed. */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<Violation> violations = new ArrayList<>();
        ex.getBindingResult().getFieldErrors()
                .forEach(error -> violations.add(Violation.atPointer(pointer(error.getField()), message(error))));
        ex.getBindingResult().getGlobalErrors()
                .forEach(error -> violations.add(Violation.atPointer("#", message(error))));
        return validationProblem(ex, violations, headers, request);
    }

    /** Method validation failed: constraints directly on {@code @RequestParam}, {@code @PathVariable}... */
    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(
            HandlerMethodValidationException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        if (ex.isForReturnValue()) {
            // Our own response violated its constraints: a server bug, not the client's fault.
            return handleExceptionInternal(ex, null, headers, HttpStatus.INTERNAL_SERVER_ERROR, request);
        }
        List<Violation> violations = new ArrayList<>();
        ex.visitResults(new HandlerMethodValidationException.Visitor() {
            @Override
            public void requestParam(@Nullable RequestParam annotation, ParameterValidationResult result) {
                addParameter(annotation != null ? annotation.name() : "", result);
            }

            @Override
            public void pathVariable(PathVariable annotation, ParameterValidationResult result) {
                addParameter(annotation.name(), result);
            }

            @Override
            public void requestHeader(RequestHeader annotation, ParameterValidationResult result) {
                addParameter(annotation.name(), result);
            }

            @Override
            public void cookieValue(CookieValue annotation, ParameterValidationResult result) {
                addParameter(annotation.name(), result);
            }

            @Override
            public void matrixVariable(MatrixVariable annotation, ParameterValidationResult result) {
                addParameter(annotation.name(), result);
            }

            @Override
            public void requestBody(RequestBody annotation, ParameterErrors errors) {
                errors.getFieldErrors()
                        .forEach(error -> violations.add(Violation.atPointer(pointer(error.getField()), message(error))));
                errors.getGlobalErrors()
                        .forEach(error -> violations.add(Violation.atPointer("#", message(error))));
            }

            @Override
            public void requestPart(RequestPart annotation, ParameterErrors errors) {
                requestBody(null, errors);
            }

            @Override
            public void modelAttribute(@Nullable ModelAttribute annotation, ParameterErrors errors) {
                // Model attributes bind query parameters, so report each field as a parameter.
                errors.getFieldErrors()
                        .forEach(error -> violations.add(Violation.atParameter(error.getField(), message(error))));
            }

            @Override
            public void other(ParameterValidationResult result) {
                addParameter("", result);
            }

            /** Report the name the client used ({@code @RequestParam("page_size")}), not the Java name. */
            private void addParameter(String declaredName, ParameterValidationResult result) {
                String name = !declaredName.isEmpty() ? declaredName : result.getMethodParameter().getParameterName();
                result.getResolvableErrors()
                        .forEach(error -> violations.add(Violation.atParameter(name, message(error))));
            }
        });
        return validationProblem(ex, violations, headers, request);
    }

    /** The body couldn't be turned into the request DTO: unknown property, wrong type, broken JSON. */
    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(
            HttpMessageNotReadableException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        MismatchedInputException mismatch = findCause(ex, MismatchedInputException.class);
        if (mismatch != null) {
            String detail = mismatch instanceof UnrecognizedPropertyException
                    ? "is not a known property"
                    : "has an invalid value or type";
            return validationProblem(ex, List.of(Violation.atPointer(pointer(mismatch.getPath()), detail)), headers, request);
        }
        // Jackson's message names our internal classes, so it is never passed on to the client.
        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, ProblemTypes.MALFORMED_REQUEST_BODY,
                "Malformed request body", "The request body is missing or is not valid JSON.");
        return handleExceptionInternal(ex, problem, headers, HttpStatus.BAD_REQUEST, request);
    }

    // --- Helpers ---------------------------------------------------------------------------------

    private ResponseEntity<Object> validationProblem(
            Exception ex, List<Violation> violations, HttpHeaders headers, WebRequest request) {
        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, ProblemTypes.VALIDATION_ERROR,
                "Invalid request", "The request contains invalid fields or parameters.");
        problem.setProperty("errors", violations.stream().sorted(Violation.ORDER).toList());
        log.debug("Rejected invalid request: {}", violations);
        return handleExceptionInternal(ex, problem, headers, HttpStatus.BAD_REQUEST, request);
    }

    private static ProblemDetail problem(HttpStatus status, URI type, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(type);
        problem.setTitle(title);
        return problem;
    }

    /** The request path as the problem's {@code instance}; {@code null} if it isn't a valid URI. */
    static @Nullable URI toInstance(String requestUri) {
        try {
            return URI.create(requestUri);
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    private static String message(MessageSourceResolvable error) {
        String message = error.getDefaultMessage();
        return message != null ? message : "is invalid";
    }

    /** Bean property path ({@code company.name}, {@code tags[0]}) to a JSON Pointer ({@code #/company/name}). */
    static String pointer(String propertyPath) {
        StringBuilder pointer = new StringBuilder("#");
        for (String token : propertyPath.split("[.\\[\\]]+")) {
            if (!token.isEmpty()) {
                pointer.append('/').append(escape(token));
            }
        }
        return pointer.toString();
    }

    /** Jackson's location of the failing property to a JSON Pointer. */
    static String pointer(List<JacksonException.Reference> path) {
        StringBuilder pointer = new StringBuilder("#");
        for (JacksonException.Reference reference : path) {
            String token = reference.getPropertyName() != null
                    ? reference.getPropertyName()
                    : String.valueOf(reference.getIndex());
            pointer.append('/').append(escape(token));
        }
        return pointer.toString();
    }

    /** RFC 6901: "~" and "/" inside a token are escaped as "~0" and "~1". */
    private static String escape(String token) {
        return token.replace("~", "~0").replace("/", "~1");
    }

    private static <T extends Throwable> @Nullable T findCause(Throwable ex, Class<T> type) {
        for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
            if (type.isInstance(cause)) {
                return type.cast(cause);
            }
        }
        return null;
    }
}
