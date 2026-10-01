package com.shardkv.storage;

import java.util.Optional;
import java.util.Set;

public interface KeyValueStore {

    void put(String key, StoredRecord record);

    Optional<StoredRecord> get(String key);

    Set<String> findByIndex(String field, Object value);
}
