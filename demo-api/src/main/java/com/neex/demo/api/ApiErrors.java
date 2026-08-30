package com.neex.demo.api;

import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;
import java.util.NoSuchElementException;

/** Errors as JSON, field by field — the thing you want to see when you comment a field out. */
@RestControllerAdvice
public class ApiErrors {

    @Schema(description = "Validation problems, one per field")
    public record ErrorBody(String error, List<FieldError> errors) {
    }

    public record FieldError(String field, String message) {
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorBody> invalid(MethodArgumentNotValidException e) {
        List<FieldError> errors = e.getBindingResult().getFieldErrors().stream()
                .map(f -> new FieldError(f.getField(), f.getDefaultMessage()))
                .sorted((a, b) -> a.field().compareTo(b.field()))
                .toList();
        return ResponseEntity.badRequest().body(new ErrorBody("validation failed", errors));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorBody> unreadable(HttpMessageNotReadableException e) {
        String reason = e.getMostSpecificCause().getMessage();
        return ResponseEntity.badRequest().body(new ErrorBody("unreadable body: " + (reason == null ? "" : reason.lines().findFirst().orElse("")), List.of()));
    }

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<ErrorBody> notFound(NoSuchElementException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ErrorBody(e.getMessage(), List.of()));
    }

    @ExceptionHandler(OrderService.ConflictException.class)
    public ResponseEntity<ErrorBody> conflict(OrderService.ConflictException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorBody(e.getMessage(), List.of()));
    }
}
