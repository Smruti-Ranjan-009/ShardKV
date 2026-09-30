package com.shardkv.api;

import jakarta.validation.constraints.NotNull;

public record PutValueRequest(@NotNull(message = "value is required") String value) {
}
