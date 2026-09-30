package com.shardkv.common;

import com.shardkv.routing.NodeCommunicationException;
import com.shardkv.storage.StorageException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(KeyNotFoundException.class)
    public ProblemDetail handleKeyNotFound(KeyNotFoundException exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, exception.getMessage());
        problem.setTitle("Key not found");
        return problem;
    }

    @ExceptionHandler(StorageException.class)
    public ProblemDetail handleStorageFailure(StorageException exception) {
        LOGGER.error("Storage operation failed", exception);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "The storage operation could not be completed");
        problem.setTitle("Storage failure");
        return problem;
    }

    @ExceptionHandler(NodeCommunicationException.class)
    public ProblemDetail handleNodeCommunicationFailure(NodeCommunicationException exception) {
        LOGGER.error("Owner-node communication failed", exception);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.SERVICE_UNAVAILABLE,
                "The owner node could not complete the request");
        problem.setTitle("Owner node unavailable");
        return problem;
    }
}
