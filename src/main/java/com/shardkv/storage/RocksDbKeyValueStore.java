package com.shardkv.storage;

import com.shardkv.config.StorageProperties;
import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import org.rocksdb.Options;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;
import org.rocksdb.WriteOptions;
import org.springframework.stereotype.Repository;

@Repository
public final class RocksDbKeyValueStore implements KeyValueStore, AutoCloseable {

    private final Options options;
    private final WriteOptions writeOptions;
    private final RocksDB database;
    private final ReadWriteLock lifecycleLock = new ReentrantReadWriteLock();
    private boolean closed;

    public RocksDbKeyValueStore(StorageProperties properties) {
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
    }

    @Override
    public void put(String key, String value) {
        Lock lock = lifecycleLock.readLock();
        lock.lock();
        try {
            ensureOpen();
            database.put(writeOptions, encode(key), encode(value));
        } catch (RocksDBException exception) {
            throw new StorageException("Failed to store value", exception);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public Optional<String> get(String key) {
        Lock lock = lifecycleLock.readLock();
        lock.lock();
        try {
            ensureOpen();
            byte[] value = database.get(encode(key));
            return value == null ? Optional.empty() : Optional.of(decode(value));
        } catch (RocksDBException exception) {
            throw new StorageException("Failed to read value", exception);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void delete(String key) {
        Lock lock = lifecycleLock.readLock();
        lock.lock();
        try {
            ensureOpen();
            database.delete(writeOptions, encode(key));
        } catch (RocksDBException exception) {
            throw new StorageException("Failed to delete value", exception);
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

    private static String decode(byte[] value) {
        return new String(value, StandardCharsets.UTF_8);
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("RocksDB storage is closed");
        }
    }
}
