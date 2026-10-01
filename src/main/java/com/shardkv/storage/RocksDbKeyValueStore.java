package com.shardkv.storage;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shardkv.config.StorageProperties;
import com.shardkv.document.Document;
import com.shardkv.document.DocumentCodec;
import com.shardkv.index.IndexKeyCodec;
import com.shardkv.index.IndexingProperties;
import com.shardkv.index.UnknownIndexFieldException;
import com.shardkv.observability.ShardKvMetrics;
import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;
import java.util.LinkedHashSet;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import org.rocksdb.Options;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;
import org.rocksdb.RocksIterator;
import org.rocksdb.WriteBatch;
import org.rocksdb.WriteOptions;
import org.springframework.stereotype.Repository;

@Repository
public final class RocksDbKeyValueStore implements KeyValueStore, AutoCloseable {

    private final Options options;
    private final WriteOptions writeOptions;
    private final RocksDB database;
    private final ObjectMapper objectMapper;
    private final DocumentCodec documentCodec;
    private final IndexKeyCodec indexKeyCodec;
    private final IndexingProperties indexingProperties;
    private final ShardKvMetrics metrics;
    private final ReadWriteLock lifecycleLock = new ReentrantReadWriteLock();
    private boolean closed;

    public RocksDbKeyValueStore(
            StorageProperties properties,
            ObjectMapper objectMapper,
            DocumentCodec documentCodec,
            IndexKeyCodec indexKeyCodec,
            IndexingProperties indexingProperties,
            ShardKvMetrics metrics) {
        Path dataDirectory = properties.dataDirectory().toAbsolutePath().normalize();

        loadNativeLibrary();

        Options newOptions = null;
        WriteOptions newWriteOptions = null;
        RocksDB newDatabase = null;

        try {
            newOptions = new Options();
            newOptions.setCreateIfMissing(true);

            newWriteOptions = new WriteOptions();
            newWriteOptions.setDisableWAL(false);
            newWriteOptions.setSync(true);

            Files.createDirectories(dataDirectory);
            newDatabase = RocksDB.open(newOptions, dataDirectory.toString());
        } catch (Throwable failure) {
            closeAfterInitializationFailure(newDatabase, failure);
            closeAfterInitializationFailure(newWriteOptions, failure);
            closeAfterInitializationFailure(newOptions, failure);

            if (failure instanceof Error error) {
                throw error;
            }
            throw new StorageException("Failed to initialize RocksDB storage", failure);
        }

        this.database = newDatabase;
        this.options = newOptions;
        this.writeOptions = newWriteOptions;
        this.objectMapper = objectMapper;
        this.documentCodec = documentCodec;
        this.indexKeyCodec = indexKeyCodec;
        this.indexingProperties = indexingProperties;
        this.metrics = metrics;
    }

    @Override
    public void put(String key, StoredRecord record) {
        Lock lock = lifecycleLock.readLock();
        lock.lock();
        try {
            ensureOpen();
            byte[] keyBytes = encode(key);
            byte[] previousBytes = database.get(keyBytes);
            try (WriteBatch batch = new WriteBatch()) {
                if (previousBytes != null) {
                    removeIndexes(batch, key, decodeRecord(previousBytes));
                }
                addIndexes(batch, key, record);
                batch.put(keyBytes, encodeRecord(record));
                database.write(writeOptions, batch);
            }
            metrics.storageOperation(record.tombstone() ? "delete" : "put", true);
        } catch (RocksDBException exception) {
            metrics.storageOperation(record.tombstone() ? "delete" : "put", false);
            throw new StorageException("Failed to store value", exception);
        } catch (RuntimeException exception) {
            metrics.storageOperation(record.tombstone() ? "delete" : "put", false);
            throw exception;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public Optional<StoredRecord> get(String key) {
        Lock lock = lifecycleLock.readLock();
        lock.lock();
        try {
            ensureOpen();
            byte[] value = database.get(encode(key));
            Optional<StoredRecord> result = value == null
                    ? Optional.empty()
                    : Optional.of(decodeRecord(value));
            metrics.storageOperation("get", true);
            return result;
        } catch (RocksDBException exception) {
            metrics.storageOperation("get", false);
            throw new StorageException("Failed to read value", exception);
        } catch (RuntimeException exception) {
            metrics.storageOperation("get", false);
            throw exception;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public Set<String> findByIndex(String field, Object value) {
        if (!indexingProperties.isIndexed(field)) {
            throw new UnknownIndexFieldException(field);
        }
        Object normalized = documentCodec.normalizeScalar(value);
        byte[] prefix = indexKeyCodec.prefix(field, normalized);
        Lock lock = lifecycleLock.readLock();
        lock.lock();
        try {
            ensureOpen();
            Set<String> keys = new LinkedHashSet<>();
            try (RocksIterator iterator = database.newIterator()) {
                iterator.seek(prefix);
                while (iterator.isValid() && indexKeyCodec.hasPrefix(iterator.key(), prefix)) {
                    keys.add(indexKeyCodec.keyFromEntry(iterator.key(), prefix.length));
                    iterator.next();
                }
                iterator.status();
            }
            return Set.copyOf(keys);
        } catch (RocksDBException | IllegalArgumentException exception) {
            throw new StorageException("Failed to query secondary index", exception);
        } finally {
            lock.unlock();
        }
    }

    @PreDestroy
    @Override
    public void close() {
        Lock lock = lifecycleLock.writeLock();
        lock.lock();
        try {
            if (closed) {
                return;
            }
            closed = true;
            database.close();
            writeOptions.close();
            options.close();
        } finally {
            lock.unlock();
        }
    }

    private static void loadNativeLibrary() {
        try {
            RocksDB.loadLibrary();
        } catch (RuntimeException | UnsatisfiedLinkError error) {
            throw new StorageException("Failed to load the native RocksDB library", error);
        }
    }

    private static void closeAfterInitializationFailure(AutoCloseable resource, Throwable failure) {
        if (resource == null) {
            return;
        }

        try {
            resource.close();
        } catch (Throwable closeFailure) {
            failure.addSuppressed(closeFailure);
        }
    }

    private static byte[] encode(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private byte[] encodeRecord(StoredRecord record) {
        try {
            return objectMapper.writeValueAsBytes(record);
        } catch (JsonProcessingException exception) {
            throw new StorageException("Failed to encode stored record", exception);
        }
    }

    private StoredRecord decodeRecord(byte[] value) {
        try {
            return objectMapper.readValue(value, StoredRecord.class);
        } catch (Exception exception) {
            throw new StorageException("Failed to decode stored record", exception);
        }
    }

    private void removeIndexes(WriteBatch batch, String key, StoredRecord record) throws RocksDBException {
        if (record.tombstone()) {
            return;
        }
        Optional<Document> document = documentCodec.decode(record.value());
        if (document.isEmpty()) {
            return;
        }
        for (String field : indexingProperties.fields()) {
            Object value = document.get().fields().get(field);
            if (value != null) {
                batch.delete(indexKeyCodec.entry(field, value, key));
            }
        }
    }

    private void addIndexes(WriteBatch batch, String key, StoredRecord record) throws RocksDBException {
        if (record.tombstone()) {
            return;
        }
        Optional<Document> document = documentCodec.decode(record.value());
        if (document.isEmpty()) {
            return;
        }
        for (String field : indexingProperties.fields()) {
            Object value = document.get().fields().get(field);
            if (value != null) {
                batch.put(indexKeyCodec.entry(field, value, key), new byte[0]);
            }
        }
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("RocksDB storage is closed");
        }
    }
}
