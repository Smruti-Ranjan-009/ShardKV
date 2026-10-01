package com.shardkv.query;

import com.shardkv.cluster.ClusterMembership;
import com.shardkv.cluster.ClusterNode;
import com.shardkv.document.DocumentResult;
import com.shardkv.routing.NodeClient;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

@Service
public class DistributedQueryService {

    private final ClusterMembership membership;
    private final LocalQueryService localQueryService;
    private final NodeClient nodeClient;
    private final QueryProperties properties;
    private final Executor queryExecutor;

    public DistributedQueryService(
            ClusterMembership membership,
            LocalQueryService localQueryService,
            NodeClient nodeClient,
            QueryProperties properties,
            @Qualifier("queryExecutor") Executor queryExecutor) {
        this.membership = membership;
        this.localQueryService = localQueryService;
        this.nodeClient = nodeClient;
        this.properties = properties;
        this.queryExecutor = queryExecutor;
    }

    public QueryResponse query(QueryRequest request) {
        Map<String, Object> filters = localQueryService.validateAndNormalize(request);
        QueryRequest normalizedRequest = new QueryRequest(filters);
        int perNodeLimit = Math.addExact(properties.maxResults(), 1);

        List<CompletableFuture<List<DocumentResult>>> futures;
        try {
            futures = membership.members().stream()
                    .map(node -> CompletableFuture.supplyAsync(
                            () -> queryNode(node, normalizedRequest, perNodeLimit), queryExecutor))
                    .toList();
        } catch (RejectedExecutionException exception) {
            throw new QueryUnavailableException("The bounded query executor is at capacity", exception);
        }

        try {
            CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();
        } catch (CompletionException exception) {
            Throwable cause = exception.getCause() == null ? exception : exception.getCause();
            throw new QueryUnavailableException(
                    "A required primary shard could not complete the query", cause);
        }

        Map<String, DocumentResult> merged = new LinkedHashMap<>();
        for (CompletableFuture<List<DocumentResult>> future : futures) {
            for (DocumentResult result : future.join()) {
                DocumentResult existing = merged.putIfAbsent(result.key(), result);
                if (existing != null && !existing.equals(result)) {
                    throw new IllegalStateException("Conflicting distributed query result for key " + result.key());
                }
            }
        }
        if (merged.size() > properties.maxResults()) {
            throw new QueryLimitExceededException(properties.maxResults());
        }

        List<DocumentResult> results = new ArrayList<>(merged.values());
        results.sort(Comparator.comparing(DocumentResult::key));
        return new QueryResponse(
                filters, results.size(), List.copyOf(results), membership.members().size(), true);
    }

    private List<DocumentResult> queryNode(ClusterNode node, QueryRequest request, int limit) {
        if (node.id().equals(membership.localNode().id())) {
            return localQueryService.query(request, limit);
        }
        return nodeClient.queryLocal(node, request, limit);
    }
}
