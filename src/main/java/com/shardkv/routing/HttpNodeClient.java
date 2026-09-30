package com.shardkv.routing;

import com.shardkv.api.PutValueRequest;
import com.shardkv.cluster.ClusterNode;
import com.shardkv.consistency.ConsistencyLevel;
import com.shardkv.storage.StoredRecord;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
public class HttpNodeClient implements NodeClient {

    private static final String INTERNAL_PRIMARY_KEY_PATH =
            "/internal/primary/kv/{key}?consistency={consistency}";
    private static final String INTERNAL_REPLICA_RECORD_PATH = "/internal/replica/record/{key}";
    private static final String INTERNAL_RECORD_PATH = "/internal/record/{key}";

    private final RestClient restClient;

    public HttpNodeClient(RestClient restClient) {
        this.restClient = restClient;
    }

    @Override
    public void putPrimary(
            ClusterNode node,
            String key,
            String value,
            ConsistencyLevel consistencyLevel) {
        try {
            restClient.put()
                    .uri(node.baseUri() + INTERNAL_PRIMARY_KEY_PATH, key, consistencyLevel)
                    .body(new PutValueRequest(value))
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientException exception) {
            throw new NodeCommunicationException(node.id(), "coordinate primary write", exception);
        }
    }

    @Override
    public void deletePrimary(
            ClusterNode node,
            String key,
            ConsistencyLevel consistencyLevel) {
        try {
            restClient.delete()
                    .uri(node.baseUri() + INTERNAL_PRIMARY_KEY_PATH, key, consistencyLevel)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientException exception) {
            throw new NodeCommunicationException(node.id(), "coordinate primary delete", exception);
        }
    }

    @Override
    public void putReplica(ClusterNode node, String key, StoredRecord record) {
        try {
            restClient.put()
                    .uri(node.baseUri() + INTERNAL_REPLICA_RECORD_PATH, key)
                    .body(record)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientException exception) {
            throw new NodeCommunicationException(node.id(), "write replica record", exception);
        }
    }

    @Override
    public Optional<StoredRecord> getRecord(ClusterNode node, String key) {
        try {
            StoredRecord response = restClient.get()
                    .uri(node.baseUri() + INTERNAL_RECORD_PATH, key)
                    .retrieve()
                    .body(StoredRecord.class);
            if (response == null) {
                throw new NodeCommunicationException(node.id(), "read stored record");
            }
            return Optional.of(response);
        } catch (HttpClientErrorException.NotFound exception) {
            return Optional.empty();
        } catch (RestClientException exception) {
            throw new NodeCommunicationException(node.id(), "read stored record", exception);
        }
    }
}
