package com.shardkv.consistency;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ConsistencyPolicyTests {

    private final ConsistencyPolicy policy = new ConsistencyPolicy(
            new ConsistencyProperties(ConsistencyLevel.QUORUM));

    @Test
    void acknowledgementCountsMatchReplicationFactorOne() {
        assertCounts(1, 1, 1, 1);
    }

    @Test
    void acknowledgementCountsMatchReplicationFactorTwo() {
        assertCounts(2, 1, 2, 2);
    }

    @Test
    void acknowledgementCountsMatchReplicationFactorThree() {
        assertCounts(3, 1, 2, 3);
    }

    @Test
    void configuredDefaultIsUsedOnlyWhenRequestDoesNotSpecifyALevel() {
        assertThat(policy.resolve(null)).isEqualTo(ConsistencyLevel.QUORUM);
        assertThat(policy.resolve(ConsistencyLevel.ONE)).isEqualTo(ConsistencyLevel.ONE);
    }

    private void assertCounts(int replicationFactor, int one, int quorum, int all) {
        assertThat(policy.requiredAcknowledgements(ConsistencyLevel.ONE, replicationFactor)).isEqualTo(one);
        assertThat(policy.requiredAcknowledgements(ConsistencyLevel.QUORUM, replicationFactor)).isEqualTo(quorum);
        assertThat(policy.requiredAcknowledgements(ConsistencyLevel.ALL, replicationFactor)).isEqualTo(all);
    }
}
