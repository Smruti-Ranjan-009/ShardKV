package com.shardkv.cluster;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class ClusterMembership {

    private final ClusterNode localNode;
    private final List<ClusterNode> members;

    public ClusterMembership(NodeProperties nodeProperties, ClusterProperties clusterProperties) {
        this.localNode = nodeProperties.asClusterNode();
        this.members = parseMembers(clusterProperties.members());
        validateLocalNode();
    }

    public ClusterNode localNode() {
        return localNode;
    }

    public List<ClusterNode> members() {
        return members;
    }

    private static List<ClusterNode> parseMembers(String configuredMembers) {
        List<ClusterNode> parsedMembers = Arrays.stream(configuredMembers.split(";", -1))
                .map(String::trim)
                .map(ClusterMembership::parseMember)
                .sorted((left, right) -> left.id().compareTo(right.id()))
                .toList();

        if (parsedMembers.isEmpty()) {
            throw new IllegalArgumentException("Cluster membership must not be empty");
        }

        Set<String> nodeIds = new HashSet<>();
        for (ClusterNode member : parsedMembers) {
            if (!nodeIds.add(member.id())) {
                throw new IllegalArgumentException("Duplicate cluster node ID: " + member.id());
            }
        }

        return List.copyOf(parsedMembers);
    }

    private static ClusterNode parseMember(String configuredMember) {
        if (configuredMember.isBlank()) {
            throw new IllegalArgumentException("Cluster membership contains an empty member");
        }

        String[] fields = configuredMember.split(",", -1);
        if (fields.length != 3) {
            throw new IllegalArgumentException(
                    "Cluster members must use the format id,host,port separated by semicolons");
        }

        int port;
        try {
            port = Integer.parseInt(fields[2].trim());
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Cluster node port must be an integer", exception);
        }

        return new ClusterNode(fields[0].trim(), fields[1].trim(), port);
    }

    private void validateLocalNode() {
        ClusterNode configuredLocalNode = members.stream()
                .filter(member -> member.id().equals(localNode.id()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Local node ID is not present in shardkv.cluster.members: " + localNode.id()));

        if (!configuredLocalNode.equals(localNode)) {
            throw new IllegalArgumentException(
                    "Local node host and port must match its cluster membership entry");
        }
    }
}
