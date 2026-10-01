package com.shardkv.query;

import java.util.LinkedHashMap;
import java.util.Map;

public record QueryRequest(Map<String, Object> filters) {

    public QueryRequest {
        filters = filters == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(filters));
    }
}
