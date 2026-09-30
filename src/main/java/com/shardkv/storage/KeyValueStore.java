package com.shardkv.storage;

import java.util.Optional;

public interface KeyValueStore {

    void put(String key, String value);

    Optional<String> get(String key);

    void delete(String key);
}
