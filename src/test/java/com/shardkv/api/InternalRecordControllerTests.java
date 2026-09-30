package com.shardkv.api;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.shardkv.service.KeyValueService;
import com.shardkv.storage.StoredRecord;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class InternalRecordControllerTests {

    @Test
    void internalReadExposesRecordMetadataAndDistinguishesNoRecord() throws Exception {
        KeyValueService localService = mock(KeyValueService.class);
        when(localService.getRecord("live")).thenReturn(Optional.of(StoredRecord.live("value", 7)));
        when(localService.getRecord("deleted")).thenReturn(Optional.of(StoredRecord.tombstone(8)));
        when(localService.getRecord("missing")).thenReturn(Optional.empty());
        MockMvc mockMvc = MockMvcBuilders
                .standaloneSetup(new InternalRecordController(localService))
                .build();

        mockMvc.perform(get("/internal/record/live"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.value").value("value"))
                .andExpect(jsonPath("$.version").value(7))
                .andExpect(jsonPath("$.tombstone").value(false));
        mockMvc.perform(get("/internal/record/deleted"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.value").doesNotExist())
                .andExpect(jsonPath("$.version").value(8))
                .andExpect(jsonPath("$.tombstone").value(true));
        mockMvc.perform(get("/internal/record/missing"))
                .andExpect(status().isNotFound());
    }
}
