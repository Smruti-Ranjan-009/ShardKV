package com.shardkv.query;

public class QueryUnavailableException extends RuntimeException {

    public QueryUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
