package com.shardkv.api;

import com.shardkv.service.KeyValueService;
import com.shardkv.storage.StoredRecord;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/record")
public class InternalRecordController {

    private final KeyValueService localKeyValueService;

    public InternalRecordController(KeyValueService localKeyValueService) {
        this.localKeyValueService = localKeyValueService;
    }

    @GetMapping("/{key}")
    public ResponseEntity<StoredRecord> get(@PathVariable String key) {
        return ResponseEntity.of(localKeyValueService.getRecord(key));
    }
}
