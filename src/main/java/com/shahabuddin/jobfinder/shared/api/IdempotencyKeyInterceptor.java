package com.shahabuddin.jobfinder.shared.api;

import com.shahabuddin.jobfinder.shared.Idempotent;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.List;

/**
 * The {@code 400}s of idempotency keys (docs/adr/0014-idempotency-keys.md), as {@code validation-error}
 * problems like any other invalid header: a missing key on an {@link Idempotent} endpoint, and a
 * malformed key on any {@code POST}/{@code PATCH}. The second matters as much: silently ignoring a
 * key the client <i>thinks</i> protects its retries would execute every retry again.
 *
 * <p>An interceptor, not part of {@link IdempotencyFilter}: only Spring MVC knows which handler, and
 * so which annotation, a request is for.
 */
final class IdempotencyKeyInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod method) || !IdempotencyFilter.METHODS.contains(request.getMethod())) {
            return true;
        }
        if (request.getHeader(IdempotencyKey.HEADER) == null) {
            if (method.hasMethodAnnotation(Idempotent.class)) {
                throw invalid(Violation.REQUIRED, "is required");
            }
            return true;
        }
        if (IdempotencyKey.of(request) == null) {
            throw invalid(Violation.INVALID_VALUE, IdempotencyKey.FORMAT);
        }
        return true;
    }

    private static InvalidRequestParametersException invalid(String code, String detail) {
        return new InvalidRequestParametersException(List.of(Violation.atParameter(IdempotencyKey.HEADER, code, detail)));
    }
}
