package com.shardkv.api;

import com.shardkv.cluster.ClusterMembership;
import com.shardkv.cluster.ConsistentHashRing;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/cluster")
public class ClusterController {

    private final ClusterMembership membership;
    private final ConsistentHashRing hashRing;

    public ClusterController(ClusterMembership membership, ConsistentHashRing hashRing) {
        this.membership = membership;
        this.hashRing = hashRing;
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
}
