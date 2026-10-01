package com.shardkv.api;

import com.shardkv.consistency.QuorumReadService;
import com.shardkv.consistency.RecoveryResult;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/recovery")
public class RecoveryController {

    private final QuorumReadService quorumReadService;

    public RecoveryController(QuorumReadService quorumReadService) {
        this.quorumReadService = quorumReadService;
    }

    @PostMapping("/{key}")
    public RecoveryResult recover(@PathVariable String key) {
        return quorumReadService.recover(key);
    }
}
