package com.shardkv.document;

import com.shardkv.consistency.ConsistencyLevel;
import com.shardkv.routing.KeyRouter;
import org.springframework.stereotype.Service;

@Service
public class DocumentService {

    private final KeyRouter keyRouter;
    private final DocumentCodec documentCodec;

    public DocumentService(KeyRouter keyRouter, DocumentCodec documentCodec) {
        this.keyRouter = keyRouter;
        this.documentCodec = documentCodec;
    }

    public Document put(String key, Document document, ConsistencyLevel consistencyLevel) {
        Document normalized = documentCodec.normalize(document);
        keyRouter.put(key, documentCodec.encode(normalized), consistencyLevel);
        return normalized;
    }

    public Document get(String key, ConsistencyLevel consistencyLevel) {
        return documentCodec.decode(keyRouter.get(key, consistencyLevel))
                .orElseThrow(() -> new DocumentTypeMismatchException(key));
    }

    public void delete(String key, ConsistencyLevel consistencyLevel) {
        keyRouter.delete(key, consistencyLevel);
    }
}
