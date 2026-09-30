package com.shardkv.service;

import com.shardkv.common.KeyNotFoundException;
import com.shardkv.storage.KeyValueStore;
import org.springframework.stereotype.Service;

@Service
public class KeyValueService {

    private final KeyValueStore keyValueStore;

    public KeyValueService(KeyValueStore keyValueStore) {
        this.keyValueStore = keyValueStore;
    }

    public void put(String key, String value) {
        keyValueStore.put(key, value);
    }

    public String get(String key) {
        return keyValueStore.get(key).orElseThrow(() -> new KeyNotFoundException(key));
    }

    public void delete(String key) {
        keyValueStore.delete(key);
    }
}
