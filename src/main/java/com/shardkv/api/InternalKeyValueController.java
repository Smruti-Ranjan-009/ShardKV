package com.shardkv.api;

import com.shardkv.service.KeyValueService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/kv")
public class InternalKeyValueController {

    private final KeyValueService localKeyValueService;

    public InternalKeyValueController(KeyValueService localKeyValueService) {
        this.localKeyValueService = localKeyValueService;
    }

    @PutMapping("/{key}")
    public KeyValueResponse put(@PathVariable String key, @Valid @RequestBody PutValueRequest request) {
        localKeyValueService.put(key, request.value());
        return new KeyValueResponse(key, request.value());
    }

    @GetMapping("/{key}")
    public KeyValueResponse get(@PathVariable String key) {
        return new KeyValueResponse(key, localKeyValueService.get(key));
    }

    @DeleteMapping("/{key}")
    public ResponseEntity<Void> delete(@PathVariable String key) {
        localKeyValueService.delete(key);
        return ResponseEntity.noContent().build();
    }
}
