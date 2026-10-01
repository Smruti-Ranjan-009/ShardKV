package com.shardkv;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

import com.shardkv.storage.KeyValueStore;
import com.shardkv.storage.RocksDbKeyValueStore;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@AutoConfigureObservability
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ShardKvApplicationTests {

    private static final Path STORAGE_DIRECTORY = Path.of(
            System.getProperty("java.io.tmpdir"),
            "shardkv-api-tests-" + UUID.randomUUID());

    @DynamicPropertySource
    static void configureStorage(DynamicPropertyRegistry registry) {
        registry.add("shardkv.storage.data-dir", STORAGE_DIRECTORY::toString);
        registry.add("shardkv.node.port", () -> 8080);
        registry.add("shardkv.cluster.members", () -> "node-1,localhost,8080");
        registry.add("shardkv.replication.factor", () -> 1);
        registry.add("shardkv.consistency.default-level", () -> "QUORUM");
        registry.add("shardkv.failure-detection.interval", () -> "1h");
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private KeyValueStore keyValueStore;

    @Test
    void contextLoads() {
        assertThat(keyValueStore).isInstanceOf(RocksDbKeyValueStore.class);
    }

    @Test
    void putStoresAValue() throws Exception {
        mockMvc.perform(put("/kv/put-test")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\":\"hello\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.key").value("put-test"))
                .andExpect(jsonPath("$.value").value("hello"));
    }

    @Test
    void getRetrievesAStoredValue() throws Exception {
        mockMvc.perform(put("/kv/get-test")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\":\"retrievable\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/kv/get-test"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.key").value("get-test"))
                .andExpect(jsonPath("$.value").value("retrievable"));
    }

    @Test
    void missingKeyReturnsNotFound() throws Exception {
        mockMvc.perform(get("/kv/does-not-exist"))
                .andExpect(status().isNotFound());
    }

    @Test
    void deleteRemovesAStoredValue() throws Exception {
        mockMvc.perform(put("/kv/delete-test")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\":\"temporary\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(delete("/kv/delete-test"))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/kv/delete-test"))
                .andExpect(status().isNotFound());
    }

    @Test
    void healthEndpointReturnsUp() throws Exception {
        mockMvc.perform(get("/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.service").value("shardkv"));
    }

    @Test
    void actuatorExposesHealthAndPrometheusMetricsWithoutKeyLabels() throws Exception {
        String key = "metrics-secret-key";
        mockMvc.perform(put("/kv/" + key + "?consistency=QUORUM")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\":\"metrics-value\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/kv/" + key + "?consistency=QUORUM"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
        mockMvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("shardkv_storage_operations_total")))
                .andExpect(content().string(containsString("shardkv_consistency_operations_total")))
                .andExpect(content().string(not(containsString(key))))
                .andExpect(content().string(not(containsString("metrics-value"))));
    }

    @Test
    void clusterEndpointReturnsConfiguredSingleNodeCluster() throws Exception {
        mockMvc.perform(get("/cluster"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.localNode.id").value("node-1"))
                .andExpect(jsonPath("$.members.length()").value(1))
                .andExpect(jsonPath("$.members[0].id").value("node-1"))
                .andExpect(jsonPath("$.members[0].status").value("HEALTHY"))
                .andExpect(jsonPath("$.virtualNodesPerNode").value(128));
    }

    @Test
    void internalHealthEndpointIdentifiesLocalNode() throws Exception {
        mockMvc.perform(get("/internal/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nodeId").value("node-1"))
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void ownerEndpointReturnsDeterministicOwner() throws Exception {
        mockMvc.perform(get("/cluster/owner/example"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.key").value("example"))
                .andExpect(jsonPath("$.owner.id").value("node-1"));
    }

    @Test
    void replicasEndpointReturnsConfiguredPlacement() throws Exception {
        mockMvc.perform(get("/cluster/replicas/example"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.key").value("example"))
                .andExpect(jsonPath("$.replicationFactor").value(1))
                .andExpect(jsonPath("$.primary.id").value("node-1"))
                .andExpect(jsonPath("$.replicas.length()").value(0));
    }

    @Test
    void explicitConsistencyParameterIsAccepted() throws Exception {
        mockMvc.perform(put("/kv/consistent?consistency=ALL")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\":\"value\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/kv/consistent?consistency=ONE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.value").value("value"));
    }

    @Test
    void invalidConsistencyLevelReturnsBadRequest() throws Exception {
        mockMvc.perform(get("/kv/example?consistency=INVALID"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void documentApiUsesExistingConsistencyAndReturnsStructuredFields() throws Exception {
        mockMvc.perform(put("/documents/user-document?consistency=ALL")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fields":{"city":"Bengaluru","role":"SDE","experience":1,"active":true}}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.key").value("user-document"))
                .andExpect(jsonPath("$.fields.city").value("Bengaluru"));

        mockMvc.perform(get("/documents/user-document?consistency=QUORUM"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fields.role").value("SDE"))
                .andExpect(jsonPath("$.fields.experience").value(1));

        mockMvc.perform(post("/internal/index/contains/user-document")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"filters\":{\"city\":\"Bengaluru\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.present").value(true));
    }

    @Test
    void distributedQuerySupportsAndAndDeleteRemovesIndexEntries() throws Exception {
        putDocument("query-a", "QueryCity", "SDE");
        putDocument("query-b", "QueryCity", "ML");

        mockMvc.perform(post("/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"filters\":{\"city\":\"QueryCity\",\"role\":\"SDE\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.complete").value(true))
                .andExpect(jsonPath("$.nodesQueried").value(1))
                .andExpect(jsonPath("$.count").value(1))
                .andExpect(jsonPath("$.results[0].key").value("query-a"));

        mockMvc.perform(delete("/documents/query-a?consistency=ALL"))
                .andExpect(status().isNoContent());

        mockMvc.perform(post("/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"filters\":{\"city\":\"QueryCity\",\"role\":\"SDE\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(0));
    }

    @Test
    void unsupportedDocumentValuesAndUnknownQueryFieldsReturnBadRequest() throws Exception {
        mockMvc.perform(put("/documents/invalid-document")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fields\":{\"city\":{\"nested\":true}}}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"filters\":{\"notIndexed\":\"value\"}}"))
                .andExpect(status().isBadRequest());
    }

    private void putDocument(String key, String city, String role) throws Exception {
        mockMvc.perform(put("/documents/" + key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fields\":{\"city\":\"" + city
                                + "\",\"role\":\"" + role + "\"}}"))
                .andExpect(status().isOk());
    }
}
