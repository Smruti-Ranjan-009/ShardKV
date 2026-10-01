package com.shardkv.consistency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.shardkv.cluster.ClusterMembership;
import com.shardkv.cluster.ClusterNode;
import com.shardkv.cluster.ClusterProperties;
import com.shardkv.cluster.NodeProperties;
import com.shardkv.routing.NodeClient;
import com.shardkv.service.KeyValueService;
import com.shardkv.storage.StoredRecord;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ReadRepairServiceTests {

    private static final ClusterNode REMOTE = new ClusterNode("node-2", "localhost", 8082);

    private KeyValueService localService;
    private NodeClient nodeClient;
    private ReadRepairService repairService;

    @BeforeEach
    void setUp() {
        ClusterProperties properties = new ClusterProperties(
                "node-1,localhost,8081;node-2,localhost,8082",
                128,
                Duration.ofSeconds(2),
                Duration.ofSeconds(5));
        ClusterMembership membership = new ClusterMembership(
                new NodeProperties("node-1", "localhost", 8081),
                properties);
        localService = mock(KeyValueService.class);
        nodeClient = mock(NodeClient.class);
        repairService = new ReadRepairService(membership, localService, nodeClient);
    }

    @Test
    void newerReplicaIsNeverOverwrittenByOlderAuthoritativeInput() {
        StoredRecord older = StoredRecord.live("old", 4);
        StoredRecord newer = StoredRecord.live("new", 5);

        RepairSummary summary = repairService.repair(
                "key",
                Optional.of(older),
                List.of(new ReplicaReadResult(REMOTE, Optional.of(newer))));

        assertThat(summary).isEqualTo(RepairSummary.none());
        verify(nodeClient, never()).putReplica(REMOTE, "key", older);
    }

    @Test
    void missingRecordIsRepairableWithTombstone() {
        StoredRecord tombstone = StoredRecord.tombstone(8);

        RepairSummary summary = repairService.repair(
                "key",
                Optional.of(tombstone),
                List.of(new ReplicaReadResult(REMOTE, Optional.empty())));

        assertThat(summary).isEqualTo(new RepairSummary(1, 1, 0));
        verify(nodeClient).putReplica(REMOTE, "key", tombstone);
    }
}
