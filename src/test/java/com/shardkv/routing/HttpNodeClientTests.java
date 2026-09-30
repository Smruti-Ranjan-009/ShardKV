package com.shardkv.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.http.HttpMethod.PUT;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.shardkv.cluster.ClusterNode;
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
    void forwardingUsesInternalEndpointAndDoesNotReenterPublicRouting() {
        server.expect(once(), requestTo("http://localhost:8082/internal/kv/customer-42"))
                .andExpect(method(PUT))
                .andRespond(withSuccess("{\"key\":\"customer-42\",\"value\":\"value\"}", MediaType.APPLICATION_JSON));

        client.put(REMOTE_NODE, "customer-42", "value");

        server.verify();
    }

    @Test
    void remoteMissingKeyIsReturnedAsEmpty() {
        server.expect(once(), requestTo("http://localhost:8082/internal/kv/missing"))
                .andExpect(method(GET))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThat(client.get(REMOTE_NODE, "missing")).isEmpty();
        server.verify();
    }
}
