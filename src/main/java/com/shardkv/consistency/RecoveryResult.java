package com.shardkv.consistency;

public record RecoveryResult(
        String key,
        int requiredResponses,
        int successfulResponses,
        Long authoritativeVersion,
        Boolean tombstone,
        RepairSummary repairs) {
}
