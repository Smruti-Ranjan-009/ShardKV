package com.shardkv.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shardkv.cluster.ClusterMembership;
import com.shardkv.cluster.ClusterNode;
import com.shardkv.cluster.ClusterProperties;
import com.shardkv.cluster.ConsistentHashRing;
import com.shardkv.cluster.NodeProperties;
import com.shardkv.document.Document;
import com.shardkv.document.DocumentCodec;
import com.shardkv.index.IndexingProperties;
import com.shardkv.index.UnknownIndexFieldException;
import com.shardkv.storage.InMemoryKeyValueStore;
import com.shardkv.storage.StoredRecord;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LocalQueryServiceTests {

    private static final String MEMBERS =
            "node-1,localhost,8081;node-2,localhost,8082;node-3,localhost,8083";

    private DocumentCodec codec;
    private InMemoryKeyValueStore store;
    private LocalQueryService service;
    private ConsistentHashRing ring;

    @BeforeEach
    void setUp() {
        codec = new DocumentCodec(new ObjectMapper());
        IndexingProperties indexing = new IndexingProperties(List.of("city", "role"));
        store = new InMemoryKeyValueStore(indexing, codec);
        ClusterProperties clusterProperties = new ClusterProperties(
                MEMBERS, 128, Duration.ofSeconds(1), Duration.ofSeconds(1));
        ClusterMembership membership = new ClusterMembership(
                new NodeProperties("node-1", "localhost", 8081), clusterProperties);
        ring = new ConsistentHashRing(membership, clusterProperties);
        service = new LocalQueryService(store, codec, indexing, ring, membership);
    }

    @Test
    void equalityAndQueryReturnsOnlyLocalPrimaryDocumentsWithoutReplicaDuplicates() {
        String localMatch = keyOwnedBy("node-1", "local-match-");
        String remoteReplicaCopy = keyOwnedBy("node-2", "remote-copy-");
        put(localMatch, "Bengaluru", "SDE", 1);
        put(remoteReplicaCopy, "Bengaluru", "SDE", 1);
        put(keyOwnedBy("node-1", "wrong-role-"), "Bengaluru", "ML", 1);

        var results = service.query(new QueryRequest(Map.of(
                "city", "Bengaluru", "role", "SDE")), 100);

        assertThat(results).extracting(result -> result.key()).containsExactly(localMatch);
    }

    @Test
    void tombstonesAndUnknownIndexesAreExcludedOrRejected() {
        String key = keyOwnedBy("node-1", "deleted-");
        put(key, "Bengaluru", "SDE", 1);
        store.put(key, StoredRecord.tombstone(2));

        assertThat(service.query(new QueryRequest(Map.of("city", "Bengaluru")), 100)).isEmpty();
        assertThatThrownBy(() -> service.query(
                new QueryRequest(Map.of("unindexed", "value")), 100))
                .isInstanceOf(UnknownIndexFieldException.class);
    }

    private void put(String key, String city, String role, long version) {
        store.put(key, StoredRecord.live(codec.encode(
                new Document(Map.of("city", city, "role", role))), version));
    }

    private String keyOwnedBy(String nodeId, String prefix) {
        for (int index = 0; index < 10_000; index++) {
            String key = prefix + index;
            if (ring.owner(key).id().equals(nodeId)) {
                return key;
            }
        }
        throw new AssertionError("Could not find key for " + nodeId);
    }
}
