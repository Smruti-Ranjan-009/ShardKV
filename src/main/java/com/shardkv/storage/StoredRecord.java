package com.shardkv.storage;

public record StoredRecord(String value, long version, boolean tombstone) {

    public StoredRecord {
        if (version < 1) {
            throw new IllegalArgumentException("Stored record version must be greater than zero");
        }
        if (tombstone && value != null) {
            throw new IllegalArgumentException("A tombstone must not contain a value");
        }
        if (!tombstone && value == null) {
            throw new IllegalArgumentException("A live record must contain a value");
        }
    }

    public static StoredRecord live(String value, long version) {
        return new StoredRecord(value, version, false);
    }

    public static StoredRecord tombstone(long version) {
        return new StoredRecord(null, version, true);
    }
}
