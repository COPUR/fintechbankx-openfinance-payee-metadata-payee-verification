package com.enterprise.openfinance.payeeverification.infrastructure.web;

import com.enterprise.openfinance.payeeverification.domain.exception.IdempotencyKeyConflictException;
import com.enterprise.openfinance.payeeverification.domain.exception.InvalidAccountReferenceException;
import com.enterprise.openfinance.payeeverification.infrastructure.security.MissingSecurityHeaderException;
import com.enterprise.openfinance.payeeverification.infrastructure.web.dto.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.stream.Collectors;

@RestControllerAdvice
class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(MissingSecurityHeaderException.class)
    ResponseEntity<ErrorResponse> handleMissingHeader(MissingSecurityHeaderException ex, HttpServletRequest request) {
        return error(HttpStatus.UNAUTHORIZED, "AUTH_HEADER_MISSING", ex.getMessage(), request);
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    ResponseEntity<ErrorResponse> handleMissingRequestHeader(MissingRequestHeaderException ex, HttpServletRequest request) {
        return error(HttpStatus.UNAUTHORIZED, "AUTH_HEADER_MISSING", "Missing required header: " + ex.getHeaderName(), request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex, HttpServletRequest request) {
        String fields = ex.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getField)
                .sorted()
                .collect(Collectors.joining(", "));
        return error(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Invalid request fields: " + fields, request);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ErrorResponse> handleUnreadable(HttpMessageNotReadableException ex, HttpServletRequest request) {
        return error(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Request body is not valid JSON for this operation", request);
    }

    @ExceptionHandler({InvalidAccountReferenceException.class, IllegalArgumentException.class})
    ResponseEntity<ErrorResponse> handleInvalid(RuntimeException ex, HttpServletRequest request) {
        return error(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", ex.getMessage(), request);
    }

    @ExceptionHandler(IdempotencyKeyConflictException.class)
    ResponseEntity<ErrorResponse> handleConflict(IdempotencyKeyConflictException ex, HttpServletRequest request) {
        return error(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT", ex.getMessage(), request);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ErrorResponse> handleUnexpected(Exception ex, HttpServletRequest request) {
        if (ex instanceof org.springframework.web.ErrorResponse framework && framework.getStatusCode().is4xxClientError()) {
            HttpStatus status = HttpStatus.valueOf(framework.getStatusCode().value());
            return error(status, "REQUEST_REJECTED", status.getReasonPhrase(), request);
        }
        // Class name only: messages of persistence errors can echo request data.
        log.error("event=unexpected_error type={}", ex.getClass().getName());
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Unexpected error occurred", request);
    }

    private static ResponseEntity<ErrorResponse> error(HttpStatus status, String code, String message,
                                                       HttpServletRequest request) {
        String interactionId = request.getHeader("X-FAPI-Interaction-ID");
        boolean present = interactionId != null && !interactionId.isBlank();
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(status);
        if (present) {
            builder.header("X-FAPI-Interaction-ID", interactionId);
        }
        return builder.body(new ErrorResponse(code, message, present ? interactionId : "N/A",
                OffsetDateTime.now(ZoneOffset.UTC).toString()));
    }
}
