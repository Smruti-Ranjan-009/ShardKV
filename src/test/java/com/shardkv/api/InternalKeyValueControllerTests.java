package com.shardkv.api;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.shardkv.service.KeyValueService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class InternalKeyValueControllerTests {

    @Test
    void internalLogicalReadBypassesRouting() throws Exception {
        KeyValueService localService = mock(KeyValueService.class);
        when(localService.getLiveValue("internal-key")).thenReturn("local-value");
        MockMvc mockMvc = MockMvcBuilders
                .standaloneSetup(new InternalKeyValueController(localService))
                .build();

        mockMvc.perform(get("/internal/kv/internal-key"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.key").value("internal-key"))
                .andExpect(jsonPath("$.value").value("local-value"));

        verify(localService).getLiveValue("internal-key");
    }
}
