package com.shardkv.consistency;

public record RepairSummary(int attempted, int succeeded, int failed) {

    public static RepairSummary none() {
        return new RepairSummary(0, 0, 0);
    }
}
