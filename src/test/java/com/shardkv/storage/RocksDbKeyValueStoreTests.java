package com.shardkv.storage;

import static org.assertj.core.api.Assertions.assertThat;

import com.shardkv.config.StorageProperties;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RocksDbKeyValueStoreTests {

    @TempDir
    private Path temporaryDirectory;

    @Test
    void putFollowedByGetReturnsValue() {
        try (RocksDbKeyValueStore store = openStore()) {
            store.put("key", "value");

            assertThat(store.get("key")).contains("value");
        }
    }

    @Test
    void updatingKeyPersistsNewestValue() {
        try (RocksDbKeyValueStore store = openStore()) {
            store.put("key", "old-value");
            store.put("key", "new-value");
        }

        try (RocksDbKeyValueStore reopenedStore = openStore()) {
            assertThat(reopenedStore.get("key")).contains("new-value");
        }
    }

    @Test
    void deleteRemovesKey() {
        try (RocksDbKeyValueStore store = openStore()) {
            store.put("key", "value");
            store.delete("key");

            assertThat(store.get("key")).isEmpty();
        }
    }

    @Test
    void utf8KeysAndValuesRoundTrip() {
        try (RocksDbKeyValueStore store = openStore()) {
            store.put("नमस्ते-🔑", "こんにちは世界 🌍");

            assertThat(store.get("नमस्ते-🔑")).contains("こんにちは世界 🌍");
        }
    }

    @Test
    void dataSurvivesCloseAndReopen() {
        try (RocksDbKeyValueStore store = openStore()) {
            store.put("persistent-key", "persistent-value");
        }

        try (RocksDbKeyValueStore reopenedStore = openStore()) {
            assertThat(reopenedStore.get("persistent-key")).contains("persistent-value");
        }
    }

    @Test
    void deletedKeyRemainsDeletedAfterCloseAndReopen() {
        try (RocksDbKeyValueStore store = openStore()) {
            store.put("deleted-key", "value");
            store.delete("deleted-key");
        }

        try (RocksDbKeyValueStore reopenedStore = openStore()) {
            assertThat(reopenedStore.get("deleted-key")).isEmpty();
        }
    }

    private RocksDbKeyValueStore openStore() {
        return new RocksDbKeyValueStore(new StorageProperties(temporaryDirectory.toString()));
    }
}
