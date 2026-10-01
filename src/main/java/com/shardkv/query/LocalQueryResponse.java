package com.shardkv.query;

import com.shardkv.document.DocumentResult;
import java.util.List;

public record LocalQueryResponse(List<DocumentResult> results) {

    public LocalQueryResponse {
        results = List.copyOf(results);
    }
}
