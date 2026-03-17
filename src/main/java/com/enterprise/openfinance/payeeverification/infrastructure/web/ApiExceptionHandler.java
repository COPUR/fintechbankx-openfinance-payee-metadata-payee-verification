package com.enterprise.openfinance.payeeverification.infrastructure.web;

import com.enterprise.openfinance.payeeverification.infrastructure.security.MissingSecurityHeaderException;
import com.enterprise.openfinance.payeeverification.infrastructure.web.dto.ErrorResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.OffsetDateTime;
import java.util.stream.Collectors;

@RestControllerAdvice
class ApiExceptionHandler {

    @ExceptionHandler(MissingSecurityHeaderException.class)
    ResponseEntity<ErrorResponse> handleMissingHeader(MissingSecurityHeaderException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(new ErrorResponse(
                "AUTH_HEADER_MISSING",
                ex.getMessage(),
                "N/A",
                OffsetDateTime.now().toString()
        ));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getField)
                .sorted()
                .collect(Collectors.joining(", "));
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse(
                "VALIDATION_ERROR",
                "Invalid request fields: " + message,
                "N/A",
                OffsetDateTime.now().toString()
        ));
    }
}
