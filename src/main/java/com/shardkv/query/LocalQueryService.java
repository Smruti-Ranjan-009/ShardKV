package com.shardkv.query;

import com.shardkv.cluster.ClusterMembership;
import com.shardkv.cluster.ConsistentHashRing;
import com.shardkv.document.Document;
import com.shardkv.document.DocumentCodec;
import com.shardkv.document.DocumentResult;
import com.shardkv.document.InvalidDocumentException;
import com.shardkv.index.IndexingProperties;
import com.shardkv.index.UnknownIndexFieldException;
import com.shardkv.storage.KeyValueStore;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

@Service
public class LocalQueryService {

    private final KeyValueStore store;
    private final DocumentCodec documentCodec;
    private final IndexingProperties indexingProperties;
    private final ConsistentHashRing hashRing;
    private final ClusterMembership membership;

    public LocalQueryService(
            KeyValueStore store,
            DocumentCodec documentCodec,
            IndexingProperties indexingProperties,
            ConsistentHashRing hashRing,
            ClusterMembership membership) {
        this.store = store;
        this.documentCodec = documentCodec;
        this.indexingProperties = indexingProperties;
        this.hashRing = hashRing;
        this.membership = membership;
    }

    public Map<String, Object> validateAndNormalize(QueryRequest request) {
        if (request.filters().isEmpty()) {
            throw new InvalidDocumentException("At least one equality filter is required");
        }
        Map<String, Object> normalized = new LinkedHashMap<>();
        request.filters().forEach((field, value) -> {
            if (!indexingProperties.isIndexed(field)) {
                throw new UnknownIndexFieldException(field);
            }
            normalized.put(field, documentCodec.normalizeScalar(value));
        });
        return Map.copyOf(normalized);
    }

    public List<DocumentResult> query(QueryRequest request, int limit) {
        if (limit < 1) {
            throw new IllegalArgumentException("Query limit must be greater than zero");
        }
        Map<String, Object> filters = validateAndNormalize(request);
        List<Set<String>> candidates = filters.entrySet().stream()
                .map(entry -> store.findByIndex(entry.getKey(), entry.getValue()))
                .sorted(Comparator.comparingInt(Set::size))
                .toList();
        Set<String> matchingKeys = new LinkedHashSet<>(candidates.get(0));
        candidates.stream().skip(1).forEach(matchingKeys::retainAll);

        List<DocumentResult> results = new ArrayList<>();
        matchingKeys.stream().sorted().forEach(key -> {
            if (results.size() >= limit || !isLocalPrimary(key)) {
                return;
            }
            store.get(key)
                    .filter(record -> !record.tombstone())
                    .flatMap(record -> documentCodec.decode(record.value()))
                    .filter(document -> matches(document, filters))
                    .ifPresent(document -> results.add(new DocumentResult(key, document.fields())));
        });
        return List.copyOf(results);
    }

    private boolean isLocalPrimary(String key) {
        return hashRing.owner(key).id().equals(membership.localNode().id());
    }

    private boolean matches(Document document, Map<String, Object> filters) {
        return filters.entrySet().stream()
                .allMatch(entry -> entry.getValue().equals(document.fields().get(entry.getKey())));
    }
}
