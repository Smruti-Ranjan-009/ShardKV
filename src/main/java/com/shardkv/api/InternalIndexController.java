package com.shardkv.api;

import com.shardkv.query.LocalQueryService;
import com.shardkv.query.QueryRequest;
import com.shardkv.storage.KeyValueStore;
import java.util.Map;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/index")
public class InternalIndexController {

    private final KeyValueStore store;
    private final LocalQueryService queryService;

    public InternalIndexController(KeyValueStore store, LocalQueryService queryService) {
        this.store = store;
        this.queryService = queryService;
    }

    @PostMapping("/contains/{key}")
    public IndexMembershipResponse contains(
            @PathVariable String key,
            @RequestBody QueryRequest request) {
        Map<String, Object> filters = queryService.validateAndNormalize(request);
        if (filters.size() != 1) {
            throw new IllegalArgumentException("Index membership inspection requires exactly one filter");
        }
        Map.Entry<String, Object> filter = filters.entrySet().iterator().next();
        boolean present = store.findByIndex(filter.getKey(), filter.getValue()).contains(key);
        return new IndexMembershipResponse(key, filter.getKey(), present);
    }
}
