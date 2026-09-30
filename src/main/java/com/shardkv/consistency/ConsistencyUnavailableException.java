package com.shardkv.consistency;

public class ConsistencyUnavailableException extends RuntimeException {

    public ConsistencyUnavailableException(String operation, int required, int successful) {
        super("Could not satisfy " + operation + " consistency: required "
                + required + " successful responses but received " + successful);
    }
}
