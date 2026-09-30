package com.shardkv.api;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.shardkv.replication.PrimaryReplicationService;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class InternalPrimaryKeyValueControllerTests {

    @Test
    void primaryEndpointDelegatesExactlyOnceToReplicationCoordinator() throws Exception {
        PrimaryReplicationService replicationService = mock(PrimaryReplicationService.class);
        MockMvc mockMvc = MockMvcBuilders
                .standaloneSetup(new InternalPrimaryKeyValueController(replicationService))
                .build();

        mockMvc.perform(put("/internal/primary/kv/replicated-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\":\"replicated-value\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(delete("/internal/primary/kv/replicated-key"))
                .andExpect(status().isNoContent());

        verify(replicationService).put("replicated-key", "replicated-value");
        verify(replicationService).delete("replicated-key");
    }
}
