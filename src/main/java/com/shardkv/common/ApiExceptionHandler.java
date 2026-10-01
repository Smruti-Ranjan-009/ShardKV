package com.shardkv.common;

import com.shardkv.consistency.ConsistencyUnavailableException;
import com.shardkv.routing.NodeCommunicationException;
import com.shardkv.storage.RecordConflictException;
import com.shardkv.storage.StorageException;
import com.shardkv.document.DocumentTypeMismatchException;
import com.shardkv.document.InvalidDocumentException;
import com.shardkv.index.UnknownIndexFieldException;
import com.shardkv.query.QueryLimitExceededException;
import com.shardkv.query.QueryUnavailableException;
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
        LOGGER.error("Cluster-node communication failed", exception);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.SERVICE_UNAVAILABLE,
                "A required cluster node could not complete the request");
        problem.setTitle("Cluster node unavailable");
        return problem;
    }

    @ExceptionHandler(ConsistencyUnavailableException.class)
    public ProblemDetail handleConsistencyUnavailable(ConsistencyUnavailableException exception) {
        LOGGER.warn("Consistency requirement could not be satisfied", exception);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.SERVICE_UNAVAILABLE,
                "The requested consistency level could not be satisfied");
        problem.setTitle("Consistency unavailable");
        return problem;
    }

    @ExceptionHandler(RecordConflictException.class)
    public ProblemDetail handleRecordConflict(RecordConflictException exception) {
        LOGGER.error("Conflicting record versions detected", exception);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.CONFLICT,
                "Conflicting record contents were detected at the same version");
        problem.setTitle("Record version conflict");
        return problem;
    }

    @ExceptionHandler({InvalidDocumentException.class, UnknownIndexFieldException.class,
            QueryLimitExceededException.class, IllegalArgumentException.class})
    public ProblemDetail handleInvalidQueryOrDocument(RuntimeException exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, exception.getMessage());
        problem.setTitle("Invalid document or query");
        return problem;
    }

    @ExceptionHandler(DocumentTypeMismatchException.class)
    public ProblemDetail handleDocumentTypeMismatch(DocumentTypeMismatchException exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, exception.getMessage());
        problem.setTitle("Stored value is not a document");
        return problem;
    }

    @ExceptionHandler(QueryUnavailableException.class)
    public ProblemDetail handleQueryUnavailable(QueryUnavailableException exception) {
        LOGGER.warn("Distributed query could not reach every primary shard", exception);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.SERVICE_UNAVAILABLE,
                "A complete distributed query result is currently unavailable");
        problem.setTitle("Distributed query unavailable");
        return problem;
    }
}
