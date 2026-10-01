package com.shardkv.document;

import java.util.Map;

public record DocumentResult(String key, Map<String, Object> fields) {

    public DocumentResult {
        fields = Map.copyOf(fields);
    }
}
