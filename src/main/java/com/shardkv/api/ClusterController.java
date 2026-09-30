package com.shardkv.api;

import com.shardkv.cluster.ClusterMembership;
import com.shardkv.cluster.ConsistentHashRing;
import com.shardkv.replication.ReplicaPlan;
import com.shardkv.replication.ReplicaPlanner;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/cluster")
public class ClusterController {

    private final ClusterMembership membership;
    private final ConsistentHashRing hashRing;
    private final ReplicaPlanner replicaPlanner;

    public ClusterController(
            ClusterMembership membership,
            ConsistentHashRing hashRing,
            ReplicaPlanner replicaPlanner) {
        this.membership = membership;
        this.hashRing = hashRing;
        this.replicaPlanner = replicaPlanner;
    }

    @GetMapping
    public ClusterResponse cluster() {
        return new ClusterResponse(
                membership.localNode(),
                membership.members(),
                hashRing.virtualNodesPerNode());
    }

    @GetMapping("/owner/{key}")
    public KeyOwnerResponse owner(@PathVariable String key) {
        return new KeyOwnerResponse(key, hashRing.owner(key));
    }

    @GetMapping("/replicas/{key}")
    public ReplicaPlacementResponse replicas(@PathVariable String key) {
        ReplicaPlan plan = replicaPlanner.planFor(key);
        return new ReplicaPlacementResponse(
                key,
                replicaPlanner.replicationFactor(),
                plan.primary(),
                plan.replicas());
    }
}
