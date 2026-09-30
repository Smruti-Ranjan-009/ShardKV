package com.shardkv.api;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.shardkv.service.KeyValueService;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class InternalKeyValueControllerTests {

    @Test
    void internalPutBypassesRoutingAndCallsLocalServiceDirectly() throws Exception {
        KeyValueService localService = mock(KeyValueService.class);
        MockMvc mockMvc = MockMvcBuilders
                .standaloneSetup(new InternalKeyValueController(localService))
                .build();

        mockMvc.perform(put("/internal/kv/internal-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\":\"local-value\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.key").value("internal-key"))
                .andExpect(jsonPath("$.value").value("local-value"));

        verify(localService).put("internal-key", "local-value");
    }
}
