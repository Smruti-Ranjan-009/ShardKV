package com.shardkv.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.shardkv.storage.InMemoryKeyValueStore;
import com.shardkv.storage.RecordConflictException;
import com.shardkv.storage.StoredRecord;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class KeyValueServiceTests {

    private KeyValueService service;

    @BeforeEach
    void setUp() {
        service = new KeyValueService(new InMemoryKeyValueStore(), new StripedKeyLock());
    }

    @Test
    void mutationsIncrementVersionsAcrossValuesAndTombstones() {
        assertThat(service.putNextVersion("key", "A")).isEqualTo(StoredRecord.live("A", 1));
        assertThat(service.putNextVersion("key", "B")).isEqualTo(StoredRecord.live("B", 2));
        assertThat(service.tombstoneNextVersion("key")).isEqualTo(StoredRecord.tombstone(3));
        assertThat(service.putNextVersion("key", "C")).isEqualTo(StoredRecord.live("C", 4));
    }

    @Test
    void staleReplicaRecordNeverOverwritesNewerLocalRecord() {
        service.applyReplicaRecord("key", StoredRecord.live("new", 5));

        service.applyReplicaRecord("key", StoredRecord.live("old", 4));

        assertThat(service.getRecord("key")).contains(StoredRecord.live("new", 5));
    }

    @Test
    void identicalReplicaRecordIsIdempotent() {
        StoredRecord record = StoredRecord.live("value", 5);
        service.applyReplicaRecord("key", record);

        service.applyReplicaRecord("key", record);

        assertThat(service.getRecord("key")).contains(record);
    }

    @Test
    void contradictorySameVersionReplicaRecordFails() {
        service.applyReplicaRecord("key", StoredRecord.live("first", 5));

        assertThatThrownBy(() -> service.applyReplicaRecord("key", StoredRecord.live("second", 5)))
                .isInstanceOf(RecordConflictException.class)
                .hasMessageContaining("version 5");
    }

    @Test
    void concurrentMutationsOfSameKeyNeverReuseAVersion() throws Exception {
        int mutationCount = 32;
        ExecutorService executor = Executors.newFixedThreadPool(mutationCount);
        CountDownLatch ready = new CountDownLatch(mutationCount);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<StoredRecord>> futures = new ArrayList<>();

        try {
            for (int mutation = 0; mutation < mutationCount; mutation++) {
                int value = mutation;
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return service.putNextVersion("shared-key", "value-" + value);
                }));
            }
            ready.await();
            start.countDown();

            List<Long> versions = new ArrayList<>();
            for (Future<StoredRecord> future : futures) {
                versions.add(future.get().version());
            }

            assertThat(versions).containsExactlyInAnyOrderElementsOf(
                    java.util.stream.LongStream.rangeClosed(1, mutationCount).boxed().toList());
            assertThat(service.getRecord("shared-key")).get().extracting(StoredRecord::version)
                    .isEqualTo((long) mutationCount);
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }
}
