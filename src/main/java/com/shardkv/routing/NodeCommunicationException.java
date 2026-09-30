package com.shardkv.routing;

public class NodeCommunicationException extends RuntimeException {

    public NodeCommunicationException(String nodeId, String operation, Throwable cause) {
        super("Failed to " + operation + " through cluster node " + nodeId, cause);
    }

    public NodeCommunicationException(String nodeId, String operation) {
        super("Cluster node " + nodeId + " returned an invalid response for " + operation);
    }
}
