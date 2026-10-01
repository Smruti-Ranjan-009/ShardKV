package com.shardkv.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shardkv.config.StorageProperties;
import com.shardkv.document.DocumentCodec;
import com.shardkv.document.Document;
import com.shardkv.index.IndexKeyCodec;
import com.shardkv.index.IndexingProperties;
import com.shardkv.index.UnknownIndexFieldException;
import com.shardkv.service.KeyValueService;
import com.shardkv.service.StripedKeyLock;
import com.shardkv.observability.TestMetrics;
import java.util.List;
import java.util.Map;
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

    @Test
    void documentPutCreatesOnlyConfiguredIndexEntries() {
        try (RocksDbKeyValueStore store = openStore()) {
            store.put("user-101", documentRecord(1, "Bengaluru", "SDE", "ignored"));

            assertThat(store.findByIndex("city", "Bengaluru")).containsExactly("user-101");
            assertThat(store.findByIndex("role", "SDE")).containsExactly("user-101");
            assertThatThrownBy(() -> store.findByIndex("team", "ignored"))
                    .isInstanceOf(UnknownIndexFieldException.class);
        }
    }

    @Test
    void updateAtomicallyMovesIndexMembershipAndTombstoneRemovesIt() {
        try (RocksDbKeyValueStore store = openStore()) {
            store.put("user-101", documentRecord(1, "Bengaluru", "SDE", "one"));
            store.put("user-101", documentRecord(2, "Hyderabad", "SDE", "two"));

            assertThat(store.get("user-101")).contains(documentRecord(2, "Hyderabad", "SDE", "two"));
            assertThat(store.findByIndex("city", "Bengaluru")).isEmpty();
            assertThat(store.findByIndex("city", "Hyderabad")).containsExactly("user-101");
            assertThat(store.findByIndex("role", "SDE")).containsExactly("user-101");

            store.put("user-101", StoredRecord.tombstone(3));
            assertThat(store.findByIndex("city", "Hyderabad")).isEmpty();
            assertThat(store.findByIndex("role", "SDE")).isEmpty();
        }
    }

    @Test
    void utf8IndexesAndNewestMembershipSurviveRestart() {
        try (RocksDbKeyValueStore store = openStore()) {
            store.put("用户-🔑", documentRecord(1, "ಬೆಂಗಳೂರು", "工程師", "first"));
            store.put("用户-🔑", documentRecord(2, "東京", "工程師", "second"));
        }

        try (RocksDbKeyValueStore store = openStore()) {
            assertThat(store.findByIndex("city", "ಬೆಂಗಳೂರು")).isEmpty();
            assertThat(store.findByIndex("city", "東京")).containsExactly("用户-🔑");
            assertThat(store.findByIndex("role", "工程師")).containsExactly("用户-🔑");
        }
    }

    @Test
    void replicaVersionRulesKeepIndexesAlignedDuringRepair() {
        try (RocksDbKeyValueStore store = openStore()) {
            KeyValueService service = new KeyValueService(store, new StripedKeyLock());
            StoredRecord versionTwo = documentRecord(2, "Pune", "SDE", "newest");
            service.applyReplicaRecord("repair-key", versionTwo);
            service.applyReplicaRecord("repair-key", documentRecord(1, "Bengaluru", "SDE", "stale"));

            assertThat(store.get("repair-key")).contains(versionTwo);
            assertThat(store.findByIndex("city", "Pune")).containsExactly("repair-key");
            assertThat(store.findByIndex("city", "Bengaluru")).isEmpty();

            service.applyReplicaRecord("repair-key", StoredRecord.tombstone(3));
            assertThat(store.findByIndex("city", "Pune")).isEmpty();
        }

        try (RocksDbKeyValueStore store = openStore()) {
            assertThat(store.get("repair-key")).contains(StoredRecord.tombstone(3));
            assertThat(store.findByIndex("city", "Pune")).isEmpty();
        }
    }

    private StoredRecord documentRecord(long version, String city, String role, String note) {
        ObjectMapper objectMapper = new ObjectMapper();
        DocumentCodec codec = new DocumentCodec(objectMapper);
        return StoredRecord.live(codec.encode(new Document(Map.of(
                "city", city,
                "role", role,
                "note", note))), version);
    }

    private RocksDbKeyValueStore openStore() {
        ObjectMapper objectMapper = new ObjectMapper();
        return new RocksDbKeyValueStore(
                new StorageProperties(temporaryDirectory.toString()),
                objectMapper,
                new DocumentCodec(objectMapper),
                new IndexKeyCodec(),
                new IndexingProperties(List.of("city", "role")),
                TestMetrics.create());
    }
}
