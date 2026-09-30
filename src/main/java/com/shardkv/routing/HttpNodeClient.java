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

    private static final String INTERNAL_KEY_PATH = "/internal/kv/{key}";

    private final RestClient restClient;

    public HttpNodeClient(RestClient restClient) {
        this.restClient = restClient;
    }

    @Override
    public void put(ClusterNode node, String key, String value) {
        try {
            restClient.put()
                    .uri(node.baseUri() + INTERNAL_KEY_PATH, key)
                    .body(new PutValueRequest(value))
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientException exception) {
            throw new NodeCommunicationException(node.id(), "write key", exception);
        }
    }

    @Override
    public Optional<String> get(ClusterNode node, String key) {
        try {
            KeyValueResponse response = restClient.get()
                    .uri(node.baseUri() + INTERNAL_KEY_PATH, key)
                    .retrieve()
                    .body(KeyValueResponse.class);
            if (response == null) {
                throw new NodeCommunicationException(node.id(), "read key");
            }
            return Optional.of(response.value());
        } catch (HttpClientErrorException.NotFound exception) {
            return Optional.empty();
        } catch (RestClientException exception) {
            throw new NodeCommunicationException(node.id(), "read key", exception);
        }
    }

    @Override
    public void delete(ClusterNode node, String key) {
        try {
            restClient.delete()
                    .uri(node.baseUri() + INTERNAL_KEY_PATH, key)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientException exception) {
            throw new NodeCommunicationException(node.id(), "delete key", exception);
        }
    }
}
