package com.shardkv.health;

import com.shardkv.cluster.ClusterMembership;
import com.shardkv.cluster.ClusterNode;
import com.shardkv.routing.NodeClient;
import com.shardkv.routing.NodeCommunicationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class HeartbeatMonitor {

    private static final Logger LOGGER = LoggerFactory.getLogger(HeartbeatMonitor.class);

    private final ClusterMembership membership;
    private final NodeClient nodeClient;
    private final NodeHealthTracker healthTracker;

    public HeartbeatMonitor(
            ClusterMembership membership,
            NodeClient nodeClient,
            NodeHealthTracker healthTracker) {
        this.membership = membership;
        this.nodeClient = nodeClient;
        this.healthTracker = healthTracker;
    }

    @Scheduled(
            fixedDelayString = "${shardkv.failure-detection.interval}",
            scheduler = "heartbeatTaskScheduler")
    public void probePeers() {
        for (ClusterNode node : membership.members()) {
            if (node.id().equals(membership.localNode().id())) {
                continue;
            }
            try {
                nodeClient.heartbeat(node);
                healthTracker.recordSuccess(node);
            } catch (NodeCommunicationException exception) {
                healthTracker.recordFailure(node);
                LOGGER.debug("Heartbeat to node {} failed: {}", node.id(), exception.getMessage());
            }
        }
    }
}
