package com.shardkv.api;

import com.shardkv.cluster.ClusterNode;

public record KeyOwnerResponse(String key, ClusterNode owner) {
}
