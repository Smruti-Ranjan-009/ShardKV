package com.shardkv;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class ShardKvApplicationTests {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void contextLoads() {
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
}
