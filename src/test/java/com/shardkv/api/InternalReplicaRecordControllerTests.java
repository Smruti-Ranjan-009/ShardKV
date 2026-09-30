package com.shardkv.api;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.shardkv.service.KeyValueService;
import com.shardkv.storage.StoredRecord;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class InternalReplicaRecordControllerTests {

    @Test
    void replicaEndpointAppliesRecordLocallyWithoutRoutingOrReplication() throws Exception {
        KeyValueService localService = mock(KeyValueService.class);
        MockMvc mockMvc = MockMvcBuilders
                .standaloneSetup(new InternalReplicaRecordController(localService))
                .build();

        mockMvc.perform(put("/internal/replica/record/replicated-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\":null,\"version\":8,\"tombstone\":true}"))
                .andExpect(status().isNoContent());

        verify(localService).applyReplicaRecord("replicated-key", StoredRecord.tombstone(8));
        verifyNoMoreInteractions(localService);
    }
}
