package com.shardkv.query;

import com.shardkv.document.DocumentResult;
import java.util.List;
import java.util.Map;

public record QueryResponse(
        Map<String, Object> filters,
        int count,
        List<DocumentResult> results,
        int nodesQueried,
        boolean complete) {
}
