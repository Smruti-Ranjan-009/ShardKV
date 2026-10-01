package com.shardkv.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.shardkv.cluster.ClusterMembership;
import com.shardkv.cluster.ClusterNode;
import com.shardkv.cluster.ClusterProperties;
import com.shardkv.cluster.NodeProperties;
import com.shardkv.document.DocumentResult;
import com.shardkv.routing.NodeClient;
import com.shardkv.routing.NodeCommunicationException;
import com.shardkv.observability.TestMetrics;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DistributedQueryServiceTests {

    private LocalQueryService localQueryService;
    private NodeClient nodeClient;
    private ClusterMembership membership;

    @BeforeEach
    void setUp() {
        ClusterProperties properties = new ClusterProperties(
                "node-1,localhost,8081;node-2,localhost,8082;node-3,localhost,8083",
                128, Duration.ofSeconds(1), Duration.ofSeconds(1));
        membership = new ClusterMembership(
                new NodeProperties("node-1", "localhost", 8081), properties);
        localQueryService = mock(LocalQueryService.class);
        nodeClient = mock(NodeClient.class);
    }

    @Test
    void fansOutMergesDeduplicatesAndSortsCompleteResults() {
        QueryRequest request = new QueryRequest(Map.of("city", "Bengaluru"));
        when(localQueryService.validateAndNormalize(request)).thenReturn(request.filters());
        when(localQueryService.query(request, 101)).thenReturn(List.of(result("user-3")));
        ClusterNode node2 = membership.members().get(1);
        ClusterNode node3 = membership.members().get(2);
        when(nodeClient.queryLocal(node2, request, 101)).thenReturn(List.of(result("user-1")));
        when(nodeClient.queryLocal(node3, request, 101))
                .thenReturn(List.of(result("user-2"), result("user-1")));

        QueryResponse response = service(100).query(request);

        assertThat(response.complete()).isTrue();
        assertThat(response.nodesQueried()).isEqualTo(3);
        assertThat(response.count()).isEqualTo(3);
        assertThat(response.results()).extracting(DocumentResult::key)
                .containsExactly("user-1", "user-2", "user-3");
        verify(nodeClient).queryLocal(node2, request, 101);
        verify(nodeClient).queryLocal(node3, request, 101);
    }

    @Test
    void anyUnavailableRequiredNodeFailsTheCompleteQuery() {
        QueryRequest request = new QueryRequest(Map.of("city", "Bengaluru"));
        when(localQueryService.validateAndNormalize(request)).thenReturn(request.filters());
        when(localQueryService.query(request, 101)).thenReturn(List.of());
        ClusterNode node2 = membership.members().get(1);
        ClusterNode node3 = membership.members().get(2);
        when(nodeClient.queryLocal(node2, request, 101)).thenThrow(
                new NodeCommunicationException(node2.id(), "query local secondary index"));
        when(nodeClient.queryLocal(node3, request, 101)).thenReturn(List.of());

        assertThatThrownBy(() -> service(100).query(request))
                .isInstanceOf(QueryUnavailableException.class);
    }

    @Test
    void resultLimitIsExplicitlyRejectedInsteadOfSilentlyTruncated() {
        QueryRequest request = new QueryRequest(Map.of("city", "Bengaluru"));
        when(localQueryService.validateAndNormalize(request)).thenReturn(request.filters());
        when(localQueryService.query(request, 2)).thenReturn(List.of(result("a"), result("b")));
        membership.members().stream().skip(1)
                .forEach(node -> when(nodeClient.queryLocal(node, request, 2)).thenReturn(List.of()));

        assertThatThrownBy(() -> service(1).query(request))
                .isInstanceOf(QueryLimitExceededException.class);
    }

    private DistributedQueryService service(int maxResults) {
        return new DistributedQueryService(
                membership, localQueryService, nodeClient,
                new QueryProperties(maxResults, 3, 10), Runnable::run, TestMetrics.create());
    }

    private DocumentResult result(String key) {
        return new DocumentResult(key, Map.of("city", "Bengaluru"));
    }
}
