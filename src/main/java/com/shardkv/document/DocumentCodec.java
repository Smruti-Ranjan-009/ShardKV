package com.shardkv.document;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
public class DocumentCodec {

    private static final String PREFIX = "\u001eSHARDKV_DOCUMENT_V1\u001e";
    private final ObjectMapper objectMapper;

    public DocumentCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String encode(Document document) {
        Document normalized = normalize(document);
        try {
            return PREFIX + objectMapper.writeValueAsString(normalized);
        } catch (JsonProcessingException exception) {
            throw new InvalidDocumentException("Could not encode document", exception);
        }
    }

    public Optional<Document> decode(String value) {
        if (value == null || !value.startsWith(PREFIX)) {
            return Optional.empty();
        }
        try {
            return Optional.of(normalize(objectMapper.readValue(
                    value.substring(PREFIX.length()), Document.class)));
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            throw new InvalidDocumentException("Stored document is invalid", exception);
        }
    }

    public Document normalize(Document document) {
        Map<String, Object> normalized = new LinkedHashMap<>();
        document.fields().forEach((field, value) -> {
            if (field == null || field.isBlank()) {
                throw new InvalidDocumentException("Document field names must not be blank");
            }
            normalized.put(field, normalizeScalar(value));
        });
        return new Document(normalized);
    }

    public Object normalizeScalar(Object value) {
        if (value instanceof String || value instanceof Boolean || value instanceof Long) {
            return value;
        }
        if (value instanceof Byte || value instanceof Short || value instanceof Integer) {
            return ((Number) value).longValue();
        }
        if (value instanceof Double || value instanceof Float) {
            double number = ((Number) value).doubleValue();
            if (!Double.isFinite(number)) {
                throw new InvalidDocumentException("Floating-point document values must be finite");
            }
            return number == 0.0d ? 0.0d : number;
        }
        throw new InvalidDocumentException(
                "Document values must be strings, integers, finite doubles, or booleans");
    }
}
