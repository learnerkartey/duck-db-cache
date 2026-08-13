package com.enterprise.datacache.feature.cache.api;

import com.enterprise.datacache.feature.cache.api.dto.ErrorResponse;
import com.enterprise.datacache.feature.cache.exception.DataCacheException;
import com.enterprise.datacache.feature.cache.exception.DatasetNotAvailableException;
import com.enterprise.datacache.feature.cache.exception.DatasetNotFoundException;
import com.enterprise.datacache.feature.cache.exception.QueryNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Maps core exceptions to HTTP responses. Never leaks stack traces, raw SQL, or credentials in a response body. */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler({DatasetNotFoundException.class, QueryNotFoundException.class})
    public ResponseEntity<ErrorResponse> handleNotFound(DataCacheException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ErrorResponse(e.getErrorCode(), e.getMessage()));
    }

    /**
     * A dataset with no ACTIVE version yet (still loading for the first time, or a prior load
     * failed) is a temporary condition, not a client error or a server bug - 503 with the precise
     * reason (see {@link DatasetNotAvailableException#availability()}) lets callers distinguish
     * "retry shortly" from "something needs attention".
     */
    @ExceptionHandler(DatasetNotAvailableException.class)
    public ResponseEntity<ErrorResponse> handleDatasetNotAvailable(DatasetNotAvailableException e) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(new ErrorResponse(e.getErrorCode(), e.getMessage()));
    }

    @ExceptionHandler(DataCacheException.class)
    public ResponseEntity<ErrorResponse> handleDataCacheException(DataCacheException e) {
        log.warn("event=rest-request-failed errorCode={} message={}", e.getErrorCode(), e.getMessage());
        HttpStatus status = "MISSING_QUERY_PARAMETER".equals(e.getErrorCode()) || "INVALID_CONFIGURATION".equals(e.getErrorCode())
                ? HttpStatus.BAD_REQUEST : HttpStatus.INTERNAL_SERVER_ERROR;
        return ResponseEntity.status(status).body(new ErrorResponse(e.getErrorCode(), e.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception e) {
        log.error("event=rest-request-unexpected-error", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ErrorResponse("INTERNAL_ERROR", "An unexpected error occurred"));
    }
}
