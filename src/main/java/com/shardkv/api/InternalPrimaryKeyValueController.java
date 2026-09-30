package com.shardkv.api;

import com.shardkv.replication.PrimaryReplicationService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/primary/kv")
public class InternalPrimaryKeyValueController {

    private final PrimaryReplicationService replicationService;

    public InternalPrimaryKeyValueController(PrimaryReplicationService replicationService) {
        this.replicationService = replicationService;
    }

    @PutMapping("/{key}")
    public KeyValueResponse put(@PathVariable String key, @Valid @RequestBody PutValueRequest request) {
        replicationService.put(key, request.value());
        return new KeyValueResponse(key, request.value());
    }

    @DeleteMapping("/{key}")
    public ResponseEntity<Void> delete(@PathVariable String key) {
        replicationService.delete(key);
        return ResponseEntity.noContent().build();
    }
}
