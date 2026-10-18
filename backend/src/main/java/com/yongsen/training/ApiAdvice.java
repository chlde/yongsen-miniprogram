package com.yongsen.training;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice
public class ApiAdvice {
    private static final Logger LOGGER = LoggerFactory.getLogger(ApiAdvice.class);

    public static Map<String, Object> ok(Object data) {
        return Data.map(
                "code", "OK", "requestId", Data.id(), "serverTime", Data.now(), "data", data);
    }

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Map<String, Object>> business(BusinessException exception) {
        return ResponseEntity.status(exception.status())
                .body(
                        Data.map(
                                "code",
                                exception.code(),
                                "message",
                                exception.getMessage(),
                                "requestId",
                                Data.id(),
                                "details",
                                exception.details()));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Map<String, Object>> conflict(DataIntegrityViolationException exception) {
        return business(
                new BusinessException(
                        "DUPLICATE_RECORD", "Record already exists or conflicts", 409));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> unexpected(Exception exception) {
        String requestId = Data.id();
        LOGGER.error("Unhandled request {}", requestId, exception);
        return ResponseEntity.internalServerError()
                .body(
                        Data.map(
                                "code",
                                "INTERNAL_ERROR",
                                "message",
                                "Internal error; reference " + requestId,
                                "requestId",
                                requestId));
    }
}
