package com.shardkv.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.shardkv.cluster.ClusterMembership;
import com.shardkv.cluster.ClusterProperties;
import com.shardkv.cluster.NodeProperties;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class InternalHealthControllerTests {

    @Test
    void endpointReturnsOnlyNodeIdentityAndAvailability() throws Exception {
        ClusterProperties properties = new ClusterProperties(
                "node-1,localhost,8081",
                128,
                Duration.ofSeconds(2),
                Duration.ofSeconds(5));
        ClusterMembership membership = new ClusterMembership(
                new NodeProperties("node-1", "localhost", 8081),
                properties);
        MockMvc mockMvc = MockMvcBuilders
                .standaloneSetup(new InternalHealthController(membership))
                .build();

        mockMvc.perform(get("/internal/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nodeId").value("node-1"))
                .andExpect(jsonPath("$.status").value("UP"));
    }
}
