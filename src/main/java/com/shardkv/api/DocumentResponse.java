package com.shardkv.api;

import java.util.Map;

public record DocumentResponse(String key, Map<String, Object> fields) {
}
