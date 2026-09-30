package com.shardkv.service;

import com.shardkv.common.KeyNotFoundException;
import com.shardkv.storage.KeyValueStore;
import com.shardkv.storage.RecordConflictException;
import com.shardkv.storage.StoredRecord;
import java.util.Optional;
import org.springframework.stereotype.Service;

@Service
public class KeyValueService {

    private final KeyValueStore keyValueStore;
    private final StripedKeyLock keyLock;

    public KeyValueService(KeyValueStore keyValueStore, StripedKeyLock keyLock) {
        this.keyValueStore = keyValueStore;
        this.keyLock = keyLock;
    }

    public Optional<StoredRecord> getRecord(String key) {
        return keyValueStore.get(key);
    }

    public String getLiveValue(String key) {
        StoredRecord record = getRecord(key).orElseThrow(() -> new KeyNotFoundException(key));
        if (record.tombstone()) {
            throw new KeyNotFoundException(key);
        }
        return record.value();
    }

    public StoredRecord putNextVersion(String key, String value) {
        return keyLock.withLock(key, () -> {
            StoredRecord record = StoredRecord.live(value, nextVersion(key));
            keyValueStore.put(key, record);
            return record;
        });
    }

    public StoredRecord tombstoneNextVersion(String key) {
        return keyLock.withLock(key, () -> {
            StoredRecord tombstone = StoredRecord.tombstone(nextVersion(key));
            keyValueStore.put(key, tombstone);
            return tombstone;
        });
    }

    public void applyReplicaRecord(String key, StoredRecord incoming) {
        keyLock.withLock(key, () -> {
            Optional<StoredRecord> local = keyValueStore.get(key);
            if (local.isEmpty() || incoming.version() > local.get().version()) {
                keyValueStore.put(key, incoming);
                return null;
            }
            if (incoming.version() < local.get().version()) {
                return null;
            }
            if (!incoming.equals(local.get())) {
                throw new RecordConflictException(key, incoming.version());
            }
            return null;
        });
    }

    private long nextVersion(String key) {
        return keyValueStore.get(key)
                .map(StoredRecord::version)
                .map(version -> Math.addExact(version, 1))
                .orElse(1L);
    }
}
