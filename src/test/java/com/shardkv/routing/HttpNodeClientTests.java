package com.shardkv.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpMethod.DELETE;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.http.HttpMethod.PUT;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.shardkv.cluster.ClusterNode;
import com.shardkv.consistency.ConsistencyLevel;
import com.shardkv.storage.StoredRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class HttpNodeClientTests {

    private static final ClusterNode REMOTE_NODE = new ClusterNode("node-2", "localhost", 8082);

    private MockRestServiceServer server;
    private HttpNodeClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new HttpNodeClient(builder.build());
    }

    @Test
    void heartbeatValidatesExpectedNodeIdentityAndAvailability() {
        server.expect(once(), requestTo("http://localhost:8082/internal/health"))
                .andExpect(method(GET))
                .andRespond(withSuccess(
                        "{\"nodeId\":\"node-2\",\"status\":\"UP\"}",
                        MediaType.APPLICATION_JSON));

        client.heartbeat(REMOTE_NODE);

        server.verify();
    }

    @Test
    void primaryWriteForwardingPreservesConsistencyLevel() {
        server.expect(once(), requestTo(
                        "http://localhost:8082/internal/primary/kv/customer-42?consistency=QUORUM"))
                .andExpect(method(PUT))
                .andRespond(withSuccess());

        client.putPrimary(REMOTE_NODE, "customer-42", "value", ConsistencyLevel.QUORUM);

        server.verify();
    }

    @Test
    void primaryDeleteForwardingPreservesConsistencyLevel() {
        server.expect(once(), requestTo(
                        "http://localhost:8082/internal/primary/kv/customer-42?consistency=ALL"))
                .andExpect(method(DELETE))
                .andRespond(withSuccess());

        client.deletePrimary(REMOTE_NODE, "customer-42", ConsistencyLevel.ALL);

        server.verify();
    }

    @Test
    void replicaWriteSendsCompleteVersionedRecord() {
        StoredRecord tombstone = StoredRecord.tombstone(8);
        server.expect(once(), requestTo("http://localhost:8082/internal/replica/record/customer-42"))
                .andExpect(method(PUT))
                .andExpect(content().json("{\"value\":null,\"version\":8,\"tombstone\":true}"))
                .andRespond(withSuccess());

        client.putReplica(REMOTE_NODE, "customer-42", tombstone);

        server.verify();
    }

    @Test
    void recordReadReturnsMetadataAndDistinguishesMissingRecord() {
        server.expect(once(), requestTo("http://localhost:8082/internal/record/customer-42"))
                .andExpect(method(GET))
                .andRespond(withSuccess(
                        "{\"value\":\"hello\",\"version\":7,\"tombstone\":false}",
                        MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo("http://localhost:8082/internal/record/missing"))
                .andExpect(method(GET))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThat(client.getRecord(REMOTE_NODE, "customer-42"))
                .contains(StoredRecord.live("hello", 7));
        assertThat(client.getRecord(REMOTE_NODE, "missing")).isEmpty();
        server.verify();
    }
}
