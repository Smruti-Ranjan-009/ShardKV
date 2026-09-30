package com.shardkv.common;

public class KeyNotFoundException extends RuntimeException {

    public KeyNotFoundException(String key) {
        super("No value found for key: " + key);
    }
}
