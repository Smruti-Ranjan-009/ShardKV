package com.shardkv.api;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.shardkv.consistency.QuorumReadService;
import com.shardkv.consistency.RecoveryResult;
import com.shardkv.consistency.RepairSummary;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class RecoveryControllerTests {

    @Test
    void recoveryEndpointDelegatesOnceForTheRequestedKey() throws Exception {
        QuorumReadService readService = mock(QuorumReadService.class);
        when(readService.recover("stale-key")).thenReturn(new RecoveryResult(
                "stale-key", 2, 3, 7L, false, new RepairSummary(1, 1, 0)));
        MockMvc mockMvc = MockMvcBuilders
                .standaloneSetup(new RecoveryController(readService))
                .build();

        mockMvc.perform(post("/internal/recovery/stale-key"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.key").value("stale-key"))
                .andExpect(jsonPath("$.authoritativeVersion").value(7))
                .andExpect(jsonPath("$.repairs.succeeded").value(1));

        verify(readService).recover("stale-key");
    }
}
