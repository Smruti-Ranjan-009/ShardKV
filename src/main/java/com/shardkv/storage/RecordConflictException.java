package com.shardkv.storage;

public class RecordConflictException extends RuntimeException {

    public RecordConflictException(String key, long version) {
        super("Conflicting contents for key " + key + " at version " + version);
    }
}
