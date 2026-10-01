package com.shardkv.api;

import com.shardkv.consistency.ConsistencyLevel;
import com.shardkv.consistency.ConsistencyPolicy;
import com.shardkv.document.Document;
import com.shardkv.document.DocumentService;
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
@RequestMapping("/documents")
public class DocumentController {

    private final DocumentService documentService;
    private final ConsistencyPolicy consistencyPolicy;

    public DocumentController(DocumentService documentService, ConsistencyPolicy consistencyPolicy) {
        this.documentService = documentService;
        this.consistencyPolicy = consistencyPolicy;
    }

    @PutMapping("/{key}")
    public DocumentResponse put(
            @PathVariable String key,
            @RequestBody Document document,
            @RequestParam(required = false) ConsistencyLevel consistency) {
        Document stored = documentService.put(key, document, consistencyPolicy.resolve(consistency));
        return new DocumentResponse(key, stored.fields());
    }

    @GetMapping("/{key}")
    public DocumentResponse get(
            @PathVariable String key,
            @RequestParam(required = false) ConsistencyLevel consistency) {
        Document document = documentService.get(key, consistencyPolicy.resolve(consistency));
        return new DocumentResponse(key, document.fields());
    }

    @DeleteMapping("/{key}")
    public ResponseEntity<Void> delete(
            @PathVariable String key,
            @RequestParam(required = false) ConsistencyLevel consistency) {
        documentService.delete(key, consistencyPolicy.resolve(consistency));
        return ResponseEntity.noContent().build();
    }
}
