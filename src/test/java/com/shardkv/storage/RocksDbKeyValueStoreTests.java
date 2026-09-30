package com.shardkv.storage;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shardkv.config.StorageProperties;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RocksDbKeyValueStoreTests {

    @TempDir
    private Path temporaryDirectory;

    @Test
    void versionedRecordRoundTrips() {
        StoredRecord record = StoredRecord.live("value", 1);

        try (RocksDbKeyValueStore store = openStore()) {
            store.put("key", record);

            assertThat(store.get("key")).contains(record);
        }
    }

    @Test
    void newestVersionSurvivesCloseAndReopen() {
        try (RocksDbKeyValueStore store = openStore()) {
            store.put("key", StoredRecord.live("old-value", 1));
            store.put("key", StoredRecord.live("new-value", 2));
        }

        try (RocksDbKeyValueStore reopenedStore = openStore()) {
            assertThat(reopenedStore.get("key")).contains(StoredRecord.live("new-value", 2));
        }
    }

    @Test
    void tombstoneSurvivesCloseAndReopen() {
        try (RocksDbKeyValueStore store = openStore()) {
            store.put("deleted-key", StoredRecord.live("value", 7));
            store.put("deleted-key", StoredRecord.tombstone(8));
        }

        try (RocksDbKeyValueStore reopenedStore = openStore()) {
            assertThat(reopenedStore.get("deleted-key")).contains(StoredRecord.tombstone(8));
        }
    }

    @Test
    void utf8KeysAndValuesRoundTrip() {
        StoredRecord record = StoredRecord.live("こんにちは世界 🌍", 11);

        try (RocksDbKeyValueStore store = openStore()) {
            store.put("नमस्ते-🔑", record);

            assertThat(store.get("नमस्ते-🔑")).contains(record);
        }
    }

    @Test
    void missingKeyRemainsDistinctFromTombstone() {
        try (RocksDbKeyValueStore store = openStore()) {
            store.put("deleted-key", StoredRecord.tombstone(1));

            assertThat(store.get("never-written")).isEmpty();
            assertThat(store.get("deleted-key")).contains(StoredRecord.tombstone(1));
        }
    }

    private RocksDbKeyValueStore openStore() {
        return new RocksDbKeyValueStore(
                new StorageProperties(temporaryDirectory.toString()),
                new ObjectMapper());
    }
}
