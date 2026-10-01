package com.shardkv.document;

import java.util.LinkedHashMap;
import java.util.Map;

public record Document(Map<String, Object> fields) {

    public Document {
        if (fields == null) {
            throw new InvalidDocumentException("Document fields must be provided");
        }
        fields = Map.copyOf(new LinkedHashMap<>(fields));
    }
}
