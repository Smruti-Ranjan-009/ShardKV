package com.shardkv.routing;

import com.shardkv.api.KeyValueResponse;
import com.shardkv.api.PutValueRequest;
import com.shardkv.cluster.ClusterNode;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
public class HttpNodeClient implements NodeClient {

    private static final String INTERNAL_LOCAL_KEY_PATH = "/internal/kv/{key}";
    private static final String INTERNAL_PRIMARY_KEY_PATH = "/internal/primary/kv/{key}";
    private static final String INTERNAL_REPLICA_KEY_PATH = "/internal/replica/kv/{key}";

    private final RestClient restClient;

    public HttpNodeClient(RestClient restClient) {
        this.restClient = restClient;
    }

    @Override
    public void putPrimary(ClusterNode node, String key, String value) {
        put(node, key, value, INTERNAL_PRIMARY_KEY_PATH, "coordinate primary write");
    }

    @Override
    public Optional<String> getPrimary(ClusterNode node, String key) {
        try {
            KeyValueResponse response = restClient.get()
                    .uri(node.baseUri() + INTERNAL_LOCAL_KEY_PATH, key)
                    .retrieve()
                    .body(KeyValueResponse.class);
            if (response == null) {
                throw new NodeCommunicationException(node.id(), "read primary key");
            }
            return Optional.of(response.value());
        } catch (HttpClientErrorException.NotFound exception) {
            return Optional.empty();
        } catch (RestClientException exception) {
            throw new NodeCommunicationException(node.id(), "read primary key", exception);
        }
    }

    @Override
    public void deletePrimary(ClusterNode node, String key) {
        delete(node, key, INTERNAL_PRIMARY_KEY_PATH, "coordinate primary delete");
    }

    @Override
    public void putReplica(ClusterNode node, String key, String value) {
        put(node, key, value, INTERNAL_REPLICA_KEY_PATH, "write replica");
    }

    @Override
    public void deleteReplica(ClusterNode node, String key) {
        delete(node, key, INTERNAL_REPLICA_KEY_PATH, "delete replica");
    }

    private void put(ClusterNode node, String key, String value, String path, String operation) {
        try {
            restClient.put()
                    .uri(node.baseUri() + path, key)
                    .body(new PutValueRequest(value))
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientException exception) {
            throw new NodeCommunicationException(node.id(), operation, exception);
        }
    }

    private void delete(ClusterNode node, String key, String path, String operation) {
        try {
            restClient.delete()
                    .uri(node.baseUri() + path, key)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientException exception) {
            throw new NodeCommunicationException(node.id(), operation, exception);
        }
    }
}
