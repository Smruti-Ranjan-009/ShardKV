package com.shardkv.api;

import com.shardkv.service.KeyValueService;
import com.shardkv.storage.StoredRecord;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/replica/record")
public class InternalReplicaRecordController {

    private final KeyValueService localKeyValueService;

    public InternalReplicaRecordController(KeyValueService localKeyValueService) {
        this.localKeyValueService = localKeyValueService;
    }

    @PutMapping("/{key}")
    public ResponseEntity<Void> put(@PathVariable String key, @RequestBody StoredRecord record) {
        localKeyValueService.applyReplicaRecord(key, record);
        return ResponseEntity.noContent().build();
    }
}
