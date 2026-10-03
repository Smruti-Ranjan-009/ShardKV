# ShardKV Phase 8 benchmark results

Generated exclusively from `benchmark/results/raw/*.json`; no result values are hand-entered.

## Environment

- Captured: 2026-10-02T19:28:36.1583642Z
- CPU: 11th Gen Intel(R) Core(TM) i5-11400H @ 2.70GHz
- Cores: 6 physical / 12 logical
- RAM: 15.73 GiB
- OS: Microsoft Windows 11 Home Single Language 10.0.26200, build 26200
- k6: `grafana/k6:2.3.0`
- JVM: No explicit heap flags; default Java 17 ergonomics

## Method

- Dataset: 5000 deterministic point keys plus a separate delete-key set
- Value payload: 1024 bytes
- Headline warm-up / measurement: 10s / 20s
- Headline results are medians of valid repetitions; individual runs are retained in `runs.csv`.
- Healthy runs are invalidated by failed k6 thresholds, excessive errors, or unhealthy cluster state.

## Horizontal sharding scale (RF=1)

| Nodes | RF | Consistency | Workload | VUs | Runs | Ops/s | P50 ms | P95 ms | P99 ms | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 1 | ALL | get-heavy | 50 | 3 | 4904.35 | 5.43 | 15.37 | 25.06 | 0.034% |
| 1 | 1 | ALL | mixed | 50 | 3 | 4754.85 | 8.44 | 15.33 | 20.33 | 0.000% |
| 1 | 1 | ALL | put-heavy | 50 | 3 | 2721.25 | 7.42 | 16.42 | 21.43 | 0.028% |
| 3 | 1 | ALL | get-heavy | 50 | 3 | 3008.90 | 13.15 | 25.27 | 34.55 | 0.018% |
| 3 | 1 | ALL | mixed | 50 | 3 | 2714.45 | 16.86 | 29.64 | 38.24 | 0.015% |
| 3 | 1 | ALL | put-heavy | 50 | 3 | 2317.60 | 18.23 | 31.05 | 39.79 | 0.011% |
| 5 | 1 | ALL | get-heavy | 50 | 3 | 4136.90 | 8.82 | 16.07 | 21.05 | 0.023% |
| 5 | 1 | ALL | mixed | 50 | 3 | 3693.55 | 11.59 | 20.04 | 24.96 | 0.013% |
| 5 | 1 | ALL | put-heavy | 50 | 3 | 3126.25 | 9.75 | 17.82 | 22.28 | 0.046% |

## Read-heavy scale (RF=1)

| Nodes | RF | Consistency | Workload | VUs | Runs | Ops/s | P50 ms | P95 ms | P99 ms | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 1 | ALL | get-heavy | 50 | 3 | 4904.35 | 5.43 | 15.37 | 25.06 | 0.034% |
| 3 | 1 | ALL | get-heavy | 50 | 3 | 3008.90 | 13.15 | 25.27 | 34.55 | 0.018% |
| 5 | 1 | ALL | get-heavy | 50 | 3 | 4136.90 | 8.82 | 16.07 | 21.05 | 0.023% |

## Replication cost (3 nodes, ALL)

| Nodes | RF | Consistency | Workload | VUs | Runs | Ops/s | P50 ms | P95 ms | P99 ms | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 3 | 1 | ALL | put-heavy | 10 | 3 | 1818.35 | 3.27 | 5.73 | 6.82 | 0.015% |
| 3 | 2 | ALL | put-heavy | 10 | 3 | 1338.50 | 7.77 | 10.56 | 12.38 | 0.000% |
| 3 | 3 | ALL | put-heavy | 10 | 3 | 805.40 | 9.40 | 13.57 | 15.60 | 0.033% |

## Consistency cost (3 nodes, RF=3)

| Nodes | RF | Consistency | Workload | VUs | Runs | Ops/s | P50 ms | P95 ms | P99 ms | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 3 | 3 | ALL | put-heavy | 10 | 3 | 895.05 | 10.43 | 14.42 | 17.20 | 0.022% |
| 3 | 3 | ONE | put-heavy | 10 | 3 | 1073.05 | 11.19 | 14.72 | 17.41 | 0.000% |
| 3 | 3 | QUORUM | put-heavy | 10 | 3 | 992.05 | 11.17 | 14.54 | 16.78 | 0.000% |

## Concurrency sweep (3 nodes, RF=1)

| Nodes | RF | Consistency | Workload | VUs | Runs | Ops/s | P50 ms | P95 ms | P99 ms | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 3 | 1 | ALL | get-heavy | 10 | 1 | 3652.90 | 2.22 | 4.36 | 6.04 | 0.000% |
| 3 | 1 | ALL | get-heavy | 25 | 1 | 1582.60 | 2.22 | 4.47 | 6.74 | 0.123% |
| 3 | 1 | ALL | get-heavy | 50 | 1 | 4575.40 | 8.88 | 17.47 | 23.38 | 0.039% |
| 3 | 1 | ALL | get-heavy | 100 | 1 | 4939.75 | 9.63 | 23.95 | 32.30 | 0.064% |

## Distributed query

| Nodes | RF | Consistency | Workload | VUs | Runs | Ops/s | P50 ms | P95 ms | P99 ms | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 3 | 3 | N/A | single-filter | 5 | 3 | 15.50 | 296.37 | 447.84 | 520.22 | 0.000% |
| 3 | 3 | N/A | two-filter-and | 5 | 3 | 29.00 | 165.67 | 203.65 | 235.87 | 0.000% |

## Failure under load

- Valid: True
- Failed node: node-3
- Actual node-down interval: 18.135s
- UNHEALTHY detection: 3.132s
- HEALTHY recovery after restart: 14.866s
- QUORUM error rate: 0.103%
- ALL error rate across the full run: 18.689%
- Overall P95 / P99: 12.70 ms / 23.05 ms

## Invalid or discarded runs

- `replication-cost-3n-rf2-put-heavy-all-50vu-run1.json`: k6 threshold, error-rate, or cluster-health validation failed

## Scope warning

These results describe one Windows development host using loopback networking and shared CPU, memory, disk, Docker, and JVM resources. Local multi-JVM scaling is not equivalent to multi-machine or production capacity.
