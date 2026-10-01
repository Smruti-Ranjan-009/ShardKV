package com.shardkv.api;

import com.shardkv.query.LocalQueryResponse;
import com.shardkv.query.LocalQueryService;
import com.shardkv.query.QueryRequest;
import com.shardkv.query.QueryProperties;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/query")
public class InternalQueryController {

    private final LocalQueryService queryService;
    private final QueryProperties properties;

    public InternalQueryController(LocalQueryService queryService, QueryProperties properties) {
        this.queryService = queryService;
        this.properties = properties;
    }

    @PostMapping
    public LocalQueryResponse query(
            @RequestBody QueryRequest request,
            @RequestParam(required = false) Integer limit) {
        int effectiveLimit = limit == null ? Math.addExact(properties.maxResults(), 1) : limit;
        int maximumInternalLimit = Math.addExact(properties.maxResults(), 1);
        if (effectiveLimit < 1 || effectiveLimit > maximumInternalLimit) {
            throw new IllegalArgumentException("Internal query limit is outside the configured bound");
        }
        return new LocalQueryResponse(queryService.query(request, effectiveLimit));
    }
}
