package com.shardkv.storage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shardkv.document.DocumentCodec;
import com.shardkv.index.IndexingProperties;
import com.shardkv.index.UnknownIndexFieldException;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Collectors;

public class InMemoryKeyValueStore implements KeyValueStore {

    private final ConcurrentMap<String, StoredRecord> records = new ConcurrentHashMap<>();
    private final IndexingProperties indexingProperties;
    private final DocumentCodec documentCodec;

    public InMemoryKeyValueStore() {
        this(new IndexingProperties(java.util.List.of()), new DocumentCodec(new ObjectMapper()));
    }

    public InMemoryKeyValueStore(IndexingProperties indexingProperties, DocumentCodec documentCodec) {
        this.indexingProperties = indexingProperties;
        this.documentCodec = documentCodec;
    }

    @Override
    public void put(String key, StoredRecord record) {
        records.put(key, record);
    }

    @Override
    public Optional<StoredRecord> get(String key) {
        return Optional.ofNullable(records.get(key));
    }

    @Override
    public Set<String> findByIndex(String field, Object value) {
        if (!indexingProperties.isIndexed(field)) {
            throw new UnknownIndexFieldException(field);
        }
        Object normalized = documentCodec.normalizeScalar(value);
        return records.entrySet().stream()
                .filter(entry -> !entry.getValue().tombstone())
                .filter(entry -> documentCodec.decode(entry.getValue().value())
                        .map(document -> normalized.equals(document.fields().get(field)))
                        .orElse(false))
                .map(java.util.Map.Entry::getKey)
                .collect(Collectors.toUnmodifiableSet());
    }
}
