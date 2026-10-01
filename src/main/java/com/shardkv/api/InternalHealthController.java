package com.shardkv.api;

import com.shardkv.cluster.ClusterMembership;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/health")
public class InternalHealthController {

    private final ClusterMembership membership;

    public InternalHealthController(ClusterMembership membership) {
        this.membership = membership;
    }

    @GetMapping
    public InternalHealthResponse health() {
        return new InternalHealthResponse(membership.localNode().id(), "UP");
    }
}
