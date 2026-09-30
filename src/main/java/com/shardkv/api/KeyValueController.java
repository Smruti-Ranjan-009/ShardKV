package com.shardkv.api;

import com.shardkv.routing.KeyRouter;
import com.shardkv.consistency.ConsistencyLevel;
import com.shardkv.consistency.ConsistencyPolicy;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/kv")
public class KeyValueController {

    private final KeyRouter keyRouter;
    private final ConsistencyPolicy consistencyPolicy;

    public KeyValueController(KeyRouter keyRouter, ConsistencyPolicy consistencyPolicy) {
        this.keyRouter = keyRouter;
        this.consistencyPolicy = consistencyPolicy;
    }

    @PutMapping("/{key}")
    public KeyValueResponse put(
            @PathVariable String key,
            @Valid @RequestBody PutValueRequest request,
            @RequestParam(required = false) ConsistencyLevel consistency) {
        keyRouter.put(key, request.value(), consistencyPolicy.resolve(consistency));
        return new KeyValueResponse(key, request.value());
    }

    @GetMapping("/{key}")
    public KeyValueResponse get(
            @PathVariable String key,
            @RequestParam(required = false) ConsistencyLevel consistency) {
        return new KeyValueResponse(key, keyRouter.get(key, consistencyPolicy.resolve(consistency)));
    }

    @DeleteMapping("/{key}")
    public ResponseEntity<Void> delete(
            @PathVariable String key,
            @RequestParam(required = false) ConsistencyLevel consistency) {
        keyRouter.delete(key, consistencyPolicy.resolve(consistency));
        return ResponseEntity.noContent().build();
    }
}
