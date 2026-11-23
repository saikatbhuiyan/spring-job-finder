package archfixture.violations.job.api;

import org.springframework.web.bind.annotation.RestControllerAdvice;

/** VIOLATION: a module's own exception handler; the global handler's catch-all would shadow it. */
@RestControllerAdvice
public class JobExceptionHandler {
}
