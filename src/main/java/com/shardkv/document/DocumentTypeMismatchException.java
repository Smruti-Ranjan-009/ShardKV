package com.shardkv.document;

public class DocumentTypeMismatchException extends RuntimeException {

    public DocumentTypeMismatchException(String key) {
        super("Key is not a structured document: " + key);
    }
}
