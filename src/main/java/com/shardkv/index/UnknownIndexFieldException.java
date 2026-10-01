package com.shardkv.index;

public class UnknownIndexFieldException extends RuntimeException {

    public UnknownIndexFieldException(String field) {
        super("Field is not configured for indexing: " + field);
    }
}
