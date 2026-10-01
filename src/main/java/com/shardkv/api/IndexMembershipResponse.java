package com.shardkv.api;

public record IndexMembershipResponse(String key, String field, boolean present) {
}
