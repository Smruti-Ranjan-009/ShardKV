package com.shardkv.index;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "shardkv.indexing")
public record IndexingProperties(List<String> fields) {

    public IndexingProperties {
        if (fields == null) {
            throw new IllegalArgumentException("shardkv.indexing.fields must be configured");
        }
        fields = fields.stream().map(String::trim).toList();
        Set<String> unique = new HashSet<>();
        for (String field : fields) {
            if (field.isBlank()) {
                throw new IllegalArgumentException("Indexed field names must not be blank");
            }
            if (!unique.add(field)) {
                throw new IllegalArgumentException("Duplicate indexed field: " + field);
            }
        }
        fields = List.copyOf(fields);
    }

    public boolean isIndexed(String field) {
        return fields.contains(field);
    }
}
