package com.hybrid.platform.common.error;

import com.hybrid.platform.ops.ErrorLogService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.List;
import java.util.Map;

/**
 * Converts every error into RFC 9457 problem+json with a stable machine-readable {@code code}.
 * Internal details (stack traces, SQL) are logged, never returned.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private final ErrorLogService errorLog;

    public GlobalExceptionHandler(ErrorLogService errorLog) {
        this.errorLog = errorLog;
    }

    @ExceptionHandler(ApiException.class)
    public ProblemDetail handleApi(ApiException ex, HttpServletRequest req) {
        ProblemDetail pd = problem(ex.getStatus(), ex.getCode(), ex.getMessage(), req);
        ex.getDetails().forEach(pd::setProperty);
        return pd;
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidation(MethodArgumentNotValidException ex, HttpServletRequest req) {
        ProblemDetail pd = problem(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Some fields are invalid", req);
        List<Map<String, String>> errors = ex.getBindingResult().getFieldErrors().stream()
                .map((FieldError fe) -> Map.of("field", fe.getField(),
                        "message", fe.getDefaultMessage() == null ? "invalid" : fe.getDefaultMessage()))
                .toList();
        pd.setProperty("errors", errors);
        return pd;
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ProblemDetail handleConstraint(ConstraintViolationException ex, HttpServletRequest req) {
        return problem(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", ex.getMessage(), req);
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class,
            MissingServletRequestParameterException.class, MissingRequestHeaderException.class})
    public ProblemDetail handleBadInput(Exception ex, HttpServletRequest req) {
        return problem(HttpStatus.BAD_REQUEST, "BAD_REQUEST", "Malformed request", req);
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ProblemDetail handleUploadSize(MaxUploadSizeExceededException ex, HttpServletRequest req) {
        return problem(HttpStatus.PAYLOAD_TOO_LARGE, "FILE_TOO_LARGE", "The uploaded file is too large", req);
    }

    @ExceptionHandler({AccessDeniedException.class, AuthorizationDeniedException.class})
    public ProblemDetail handleDenied(Exception ex, HttpServletRequest req) {
        return problem(HttpStatus.FORBIDDEN, "FORBIDDEN", "You do not have permission for this action", req);
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ProblemDetail handleOptimistic(OptimisticLockingFailureException ex, HttpServletRequest req) {
        return problem(HttpStatus.CONFLICT, "CONCURRENT_MODIFICATION",
                "This record was changed by someone else. Reload and try again.", req);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ProblemDetail handleIntegrity(DataIntegrityViolationException ex, HttpServletRequest req) {
        log.warn("Data integrity violation on {}: {}", req.getRequestURI(), ex.getMostSpecificCause().getMessage());
        return problem(HttpStatus.CONFLICT, "DATA_CONFLICT", "The request conflicts with existing data", req);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ProblemDetail handleMethod(HttpRequestMethodNotSupportedException ex, HttpServletRequest req) {
        return problem(HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED", "Method not allowed", req);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ProblemDetail handleNoResource(NoResourceFoundException ex, HttpServletRequest req) {
        return problem(HttpStatus.NOT_FOUND, "NOT_FOUND", "Not found", req);
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(Exception ex, HttpServletRequest req) {
        log.error("Unhandled error on {} {}", req.getMethod(), req.getRequestURI(), ex);
        errorLog.record(req.getMethod(), req.getRequestURI(), ex);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Something went wrong", req);
    }

    private ProblemDetail problem(HttpStatus status, String code, String detail, HttpServletRequest req) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, detail);
        pd.setTitle(status.getReasonPhrase());
        pd.setProperty("code", code);
        pd.setProperty("path", req.getRequestURI());
        return pd;
    }
}
