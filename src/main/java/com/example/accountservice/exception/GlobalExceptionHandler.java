package com.example.accountservice.exception;

import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One error format (RFC 7807 problem details) for every endpoint; never returns stack traces.
 * Extends ResponseEntityExceptionHandler so standard Spring MVC errors (404 unknown path, 405, bad JSON)
 * keep their correct status instead of falling into the catch-all 500.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(NotFoundException.class)
    public ProblemDetail notFound(NotFoundException ex) {
        log.info("Not found: {}", ex.getMessage());
        return problem(HttpStatus.NOT_FOUND, "NOT_FOUND", ex.getMessage());
    }

    @ExceptionHandler(BusinessRuleException.class)
    public ProblemDetail businessRule(BusinessRuleException ex) {
        log.warn("Business rule {} violated: {}", ex.getCode(), ex.getMessage());
        return problem(HttpStatus.valueOf(422), ex.getCode(), ex.getMessage());
    }

    @ExceptionHandler(ConcurrentUpdateException.class)
    public ProblemDetail concurrentUpdate(ConcurrentUpdateException ex) {
        log.warn("Concurrent update: {}", ex.getMessage());
        return ex.isPreconditionFailed()
                ? problem(HttpStatus.PRECONDITION_FAILED, "VERSION_MISMATCH", ex.getMessage())
                : problem(HttpStatus.CONFLICT, "CONCURRENT_UPDATE", ex.getMessage());
    }

    @ExceptionHandler({ConstraintViolationException.class, MethodArgumentTypeMismatchException.class,
            IllegalArgumentException.class})
    public ProblemDetail badRequest(Exception ex) {
        log.warn("Bad request: {}", ex.getMessage());
        return problem(HttpStatus.BAD_REQUEST, "BAD_REQUEST", "A request parameter or header is invalid.");
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail unexpected(Exception ex) {
        log.error("Unexpected error", ex);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Something went wrong.");
    }

    /** Field-level messages for @Valid request bodies. */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
                                                                  HttpHeaders headers, HttpStatusCode status,
                                                                  WebRequest request) {
        Map<String, String> errors = new LinkedHashMap<>();
        ex.getBindingResult().getFieldErrors()
                .forEach(fe -> errors.putIfAbsent(fe.getField(), fe.getDefaultMessage()));
        // Field names only: the rejected values may contain personal data (email, mobile number).
        log.warn("Validation failed for fields {}", errors.keySet());
        ProblemDetail pd = problem(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "One or more fields are invalid.");
        pd.setProperty("errors", errors);
        return ResponseEntity.badRequest().body(pd);
    }

    private static ProblemDetail problem(HttpStatus status, String code, String detail) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, detail);
        pd.setTitle(status.getReasonPhrase());
        pd.setProperty("code", code);
        return pd;
    }
}
