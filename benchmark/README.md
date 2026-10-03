# ShardKV benchmark harness

This directory contains the reproducible Phase 8 load harness. ShardKV nodes run as local Java processes; k6 runs from the pinned `grafana/k6:2.3.0` Docker image, so no global k6 installation is required.

The harness is intentionally separate from Maven tests. It starts only tracked ShardKV JVMs, uses isolated RocksDB directories under `benchmark/.runtime/`, validates cluster health and placement, and stops only the PIDs it created.

## Standard methodology

- Dataset: 5,000 deterministic `bench-key-*` values plus a separate 5,000-key delete set.
- Document dataset: 5,000 deterministic `bench-doc-*` documents.
- Value payload: 1,024 bytes.
- Preload: 10 VUs with `ALL`; preloading is not included in measured time.
- Warm-up: 10 seconds before every measured run.
- Measurement: 20 seconds.
- Headline repetitions: 3; summaries use the median of valid runs.
- Horizontal comparison: 50 VUs, RF=1, ALL, 1/3/5 nodes.
- Replication and consistency comparisons: 10 VUs, 3 nodes.
- Query comparison: 5 VUs, RF=3.
- Failure run: 30 VUs for 60 seconds, RF=3; node-3 is stopped and restarted.
- Healthy-run sanity threshold: error rate below 1%, with healthy cluster state before and after the run.

Workloads are deterministic:

| Workload | GET | PUT | DELETE |
| --- | ---: | ---: | ---: |
| GET-heavy | 90% | 10% | 0% |
| PUT-heavy | 30% | 70% | 0% |
| Mixed | 50% | 40% | 10% |

k6 uses bounded route-template metric names (`/kv/{key}`, `/documents/{key}`, and `/query`). User keys and values therefore do not create client-side metric cardinality.

## Prerequisites

- Java 17
- Docker Desktop with Linux containers
- the repository's Maven Wrapper

Package the application first:

```powershell
.\mvnw.cmd clean package
```

## Run the suite

Run all experiments:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass `
    -File .\benchmark\scripts\run-suite.ps1
```

Run selected experiments:

```powershell
& .\benchmark\scripts\run-suite.ps1 `
    -Experiments @('replication', 'consistency')
```

Supported experiment names are `horizontal`, `replication`, `consistency`, `concurrency`, `query`, and `failure`. The short `benchmark/configs/smoke.json` profile verifies the harness without producing publishable results:

```powershell
& .\benchmark\scripts\run-suite.ps1 `
    -ConfigPath .\benchmark\configs\smoke.json
```

The main scripts are:

| Script | Purpose |
| --- | --- |
| `start-cluster.ps1` | Starts an isolated 1-, 3-, or 5-node topology and records exact PIDs. |
| `stop-cluster.ps1` | Stops only verified, tracked ShardKV JVMs and optionally removes their run data. |
| `preload.ps1` | Loads point keys/documents and validates primary distribution and active RF. |
| `run-benchmark.ps1` | Runs warm-up plus repeated measured point/query workloads. |
| `run-failure-benchmark.ps1` | Stops/restarts node-3 during traffic and correlates Prometheus metrics. |
| `capture-environment.ps1` | Captures CPU, RAM, OS, Java, Docker, k6, and repository metadata. |
| `summarize-results.ps1` | Regenerates CSV, JSON, and Markdown summaries from raw JSON. |
| `generate-charts.ps1` | Regenerates SVG charts with zero-based axes. |

## Results

- Full generated report: [`results/summary.md`](results/summary.md)
- Individual run table: [`results/runs.csv`](results/runs.csv)
- Machine-readable medians: [`results/summary.csv`](results/summary.csv) and [`results/summary.json`](results/summary.json)
- Failure details: [`results/failure-summary.json`](results/failure-summary.json)
- Environment: [`results/environment.json`](results/environment.json)
- Charts: [`results/charts/`](results/charts/)

Raw per-run JSON, warm-up output, logs, RocksDB directories, and PID/state files are generated locally and ignored by Git.

One RF=2/ALL run at 50 VUs was deliberately discarded: it exceeded the 1% healthy-run threshold and lost cluster availability. The controlled RF comparison was rerun uniformly at 10 VUs. The invalid run remains identified in the generated summary; it is not included in medians.

## Safety and limitations

These measurements were made on one Windows laptop. All ShardKV JVMs share the same CPU, RAM, disk, and loopback network, while k6 runs through Docker Desktop. A five-JVM topology is not equivalent to five machines. Results include JVM/JIT, RocksDB compaction, local scheduling, and Docker-to-host networking effects.

The harness does not claim production capacity, cross-region behavior, or an SLO. It does not run from the normal Maven lifecycle, and it does not Dockerize ShardKV.
