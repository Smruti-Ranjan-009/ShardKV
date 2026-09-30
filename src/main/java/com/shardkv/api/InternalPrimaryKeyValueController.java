package com.shardkv.api;

import com.shardkv.replication.PrimaryReplicationService;
import com.shardkv.consistency.ConsistencyLevel;
import com.shardkv.consistency.ConsistencyPolicy;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/primary/kv")
public class InternalPrimaryKeyValueController {

    private final PrimaryReplicationService replicationService;
    private final ConsistencyPolicy consistencyPolicy;

    public InternalPrimaryKeyValueController(
            PrimaryReplicationService replicationService,
            ConsistencyPolicy consistencyPolicy) {
        this.replicationService = replicationService;
        this.consistencyPolicy = consistencyPolicy;
    }

    @PutMapping("/{key}")
    public KeyValueResponse put(
            @PathVariable String key,
            @Valid @RequestBody PutValueRequest request,
            @RequestParam(required = false) ConsistencyLevel consistency) {
        replicationService.put(key, request.value(), consistencyPolicy.resolve(consistency));
        return new KeyValueResponse(key, request.value());
    }

    @DeleteMapping("/{key}")
    public ResponseEntity<Void> delete(
            @PathVariable String key,
            @RequestParam(required = false) ConsistencyLevel consistency) {
        replicationService.delete(key, consistencyPolicy.resolve(consistency));
        return ResponseEntity.noContent().build();
    }
}
