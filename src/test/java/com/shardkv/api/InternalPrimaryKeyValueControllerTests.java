package com.shardkv.api;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.shardkv.consistency.ConsistencyLevel;
import com.shardkv.consistency.ConsistencyPolicy;
import com.shardkv.consistency.ConsistencyProperties;
import com.shardkv.replication.PrimaryReplicationService;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class InternalPrimaryKeyValueControllerTests {

    @Test
    void primaryEndpointDelegatesExactlyOnceWithRequestedConsistency() throws Exception {
        PrimaryReplicationService replicationService = mock(PrimaryReplicationService.class);
        ConsistencyPolicy policy = new ConsistencyPolicy(
                new ConsistencyProperties(ConsistencyLevel.QUORUM));
        MockMvc mockMvc = MockMvcBuilders
                .standaloneSetup(new InternalPrimaryKeyValueController(replicationService, policy))
                .build();

        mockMvc.perform(put("/internal/primary/kv/replicated-key?consistency=ONE")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\":\"replicated-value\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(delete("/internal/primary/kv/replicated-key?consistency=ALL"))
                .andExpect(status().isNoContent());

        verify(replicationService).put(
                "replicated-key", "replicated-value", ConsistencyLevel.ONE);
        verify(replicationService).delete("replicated-key", ConsistencyLevel.ALL);
    }
}
