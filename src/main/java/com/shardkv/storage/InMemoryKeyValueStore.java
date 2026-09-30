package com.shardkv.storage;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class InMemoryKeyValueStore implements KeyValueStore {

    private final ConcurrentMap<String, StoredRecord> records = new ConcurrentHashMap<>();

    @Override
    public void put(String key, StoredRecord record) {
        records.put(key, record);
    }

    @Override
    public Optional<StoredRecord> get(String key) {
        return Optional.ofNullable(records.get(key));
    }
}
