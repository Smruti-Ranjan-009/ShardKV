package com.shardkv.api;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.shardkv.service.KeyValueService;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class InternalReplicaKeyValueControllerTests {

    @Test
    void replicaPutAndDeleteOnlyTouchLocalStorage() throws Exception {
        KeyValueService localService = mock(KeyValueService.class);
        MockMvc mockMvc = MockMvcBuilders
                .standaloneSetup(new InternalReplicaKeyValueController(localService))
                .build();

        mockMvc.perform(put("/internal/replica/kv/replicated-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\":\"replica-value\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.key").value("replicated-key"))
                .andExpect(jsonPath("$.value").value("replica-value"));

        mockMvc.perform(delete("/internal/replica/kv/replicated-key"))
                .andExpect(status().isNoContent());

        verify(localService).put("replicated-key", "replica-value");
        verify(localService).delete("replicated-key");
        verifyNoMoreInteractions(localService);
    }
}
