package com.shardkv.storage;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class InMemoryKeyValueStore implements KeyValueStore {

    private final ConcurrentMap<String, String> values = new ConcurrentHashMap<>();

    @Override
    public void put(String key, String value) {
        values.put(key, value);
    }

    @Override
    public Optional<String> get(String key) {
        return Optional.ofNullable(values.get(key));
    }

    @Override
    public void delete(String key) {
        values.remove(key);
    }
}
