package com.shardkv.storage;

import java.util.Optional;

public interface KeyValueStore {

    void put(String key, StoredRecord record);

    Optional<StoredRecord> get(String key);
}
