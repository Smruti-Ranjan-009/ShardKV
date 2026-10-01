package com.shardkv.health;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;

import com.shardkv.cluster.ClusterMembership;
import com.shardkv.cluster.ClusterNode;
import com.shardkv.cluster.ClusterProperties;
import com.shardkv.cluster.NodeProperties;
import com.shardkv.routing.NodeClient;
import com.shardkv.routing.NodeCommunicationException;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class HeartbeatMonitorTests {

    private ClusterMembership membership;
    private NodeClient nodeClient;
    private NodeHealthTracker tracker;
    private HeartbeatMonitor monitor;

    @BeforeEach
    void setUp() {
        ClusterProperties clusterProperties = new ClusterProperties(
                "node-1,localhost,8081;node-2,localhost,8082;node-3,localhost,8083",
                128,
                Duration.ofSeconds(2),
                Duration.ofSeconds(5));
        membership = new ClusterMembership(
                new NodeProperties("node-1", "localhost", 8081),
                clusterProperties);
        nodeClient = mock(NodeClient.class);
        tracker = new NodeHealthTracker(
                membership,
                new FailureDetectionProperties(Duration.ofSeconds(2), 3, 2));
        monitor = new HeartbeatMonitor(membership, nodeClient, tracker);
    }

    @Test
    void probeChecksOnlyPeersAndKeepsSuccessfulPeersHealthy() {
        monitor.probePeers();

        verify(nodeClient, never()).heartbeat(membership.localNode());
        for (ClusterNode peer : membership.members().stream()
                .filter(node -> !node.equals(membership.localNode()))
                .toList()) {
            verify(nodeClient).heartbeat(peer);
            assertThat(tracker.status(peer)).isEqualTo(NodeHealthStatus.HEALTHY);
        }
    }

    @Test
    void repeatedProbeFailuresAndSuccessesDriveFailureAndRecoveryTransitions() {
        ClusterNode peer = membership.members().stream()
                .filter(node -> node.id().equals("node-2"))
                .findFirst()
                .orElseThrow();
        doThrow(new NodeCommunicationException(peer.id(), "heartbeat"))
                .when(nodeClient)
                .heartbeat(peer);

        monitor.probePeers();
        monitor.probePeers();
        monitor.probePeers();
        assertThat(tracker.status(peer)).isEqualTo(NodeHealthStatus.UNHEALTHY);

        reset(nodeClient);
        monitor.probePeers();
        assertThat(tracker.status(peer)).isEqualTo(NodeHealthStatus.UNHEALTHY);
        monitor.probePeers();
        assertThat(tracker.status(peer)).isEqualTo(NodeHealthStatus.HEALTHY);
    }
}
