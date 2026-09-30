package com.shardkv;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.shardkv.storage.KeyValueStore;
import com.shardkv.storage.RocksDbKeyValueStore;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
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
    void clusterEndpointReturnsConfiguredSingleNodeCluster() throws Exception {
        mockMvc.perform(get("/cluster"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.localNode.id").value("node-1"))
                .andExpect(jsonPath("$.members.length()").value(1))
                .andExpect(jsonPath("$.members[0].id").value("node-1"))
                .andExpect(jsonPath("$.virtualNodesPerNode").value(128));
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
}
