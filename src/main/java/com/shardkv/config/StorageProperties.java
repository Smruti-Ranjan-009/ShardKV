package com.shardkv.config;

import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "shardkv.storage")
public record StorageProperties(String dataDir) {

    public StorageProperties {
        if (dataDir == null || dataDir.isBlank()) {
            throw new IllegalArgumentException("shardkv.storage.data-dir must be configured");
        }
    }

    public Path dataDirectory() {
        return Path.of(dataDir);
    }
}
