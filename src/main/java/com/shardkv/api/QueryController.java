package com.shardkv.api;

import com.shardkv.query.DistributedQueryService;
import com.shardkv.query.QueryRequest;
import com.shardkv.query.QueryResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/query")
public class QueryController {

    private final DistributedQueryService queryService;

    public QueryController(DistributedQueryService queryService) {
        this.queryService = queryService;
    }

    @PostMapping
    public QueryResponse query(@RequestBody QueryRequest request) {
        return queryService.query(request);
    }
}
