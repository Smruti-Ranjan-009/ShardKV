# ShardKV

ShardKV is an incremental Java project for exploring the foundations of a distributed key-value store. Phase 7 adds production-style application instrumentation and a reproducible local Prometheus/Grafana stack to the existing durable quorum-based cluster.

ShardKV remains a development system. It does not automatically promote write primaries and does not claim linearizability, consensus, or complete partition tolerance.

## Current status: Phase 7

Implemented:

- Java 17, Spring Boot 3, and the Maven Wrapper
- RocksDB storage with native WAL enabled and `sync=true`
- static membership, SHA-256 consistent hashing, and virtual nodes
- deterministic primary and replica placement
- versioned records and durable tombstones
- `ONE`, `QUORUM`, and `ALL` consistency
- heartbeat-based `HEALTHY`, `SUSPECT`, and `UNHEALTHY` tracking
- health-aware replica reads and read failover
- highest-version read reconciliation and best-effort read repair
- bounded per-key recovery through an internal endpoint
- durable repair using the existing replica version rules
- structured scalar documents alongside the backward-compatible `/kv` API
- configured durable secondary indexes with atomic record/index writes
- equality and AND queries with parallel all-node fan-out
- primary-shard filtering, defensive deduplication, and bounded query results
- Spring Boot Actuator health and Prometheus endpoints
- low-cardinality Micrometer metrics for storage, consistency, replication, health, repair, and queries
- provisioned Prometheus scraping and the `ShardKV Cluster Overview` Grafana dashboard

Not implemented: automatic write-primary failover, dynamic membership, gossip, hinted handoff, full anti-entropy, consensus, automatic migration, SQL, range/full-text queries, distributed index-query failover, or load benchmarking.

## Architecture

```text
                           Client
                             |
                      any ShardKV node
                             |
                         KeyRouter
                 writes /               \ reads
                       /                 \
          deterministic primary      ReplicaPlanner
                    |                 /     |     \
          versioned durable write  primary replica replica
                    |                 \     |     /
            synchronous replicas     health-aware reads
                                      highest version
                                            |
                                  best-effort read repair

     PUT /documents/{key} --> existing versioned mutation pipeline
                                  |
                         RocksDB WriteBatch
                         record + local indexes

     POST /query --> parallel fan-out to every physical node
                         |       |       |
                    local primary-owned index matches only
                         \       |       /
                       merge + deduplicate + limit

       HeartbeatMonitor ----> configured peer /internal/health
              |
       NodeHealthTracker
       HEALTHY / SUSPECT / UNHEALTHY

       ShardKV Node 1 ----\
       ShardKV Node 2 -----+--> Prometheus --> Grafana
       ShardKV Node 3 ----/
```

Every node builds the same immutable hash ring from identical static membership. SHA-256 hashes UTF-8 ring identifiers, and replica placement walks clockwise while skipping duplicate physical nodes.

The deterministic primary remains the only mutation coordinator and version generator. Health state never changes ownership or promotes a replica.

Instrumentation is observational: it does not change routing, acknowledgement, repair, failure-detection, or durability decisions. Spring supplies the normal HTTP server metrics; `ShardKvMetrics` centralizes domain metrics so application code does not scatter raw registry access.

## Documents and secondary indexes

Documents contain a flat `fields` map. Supported scalar values are strings, integers/longs, finite doubles, and booleans; nested objects, arrays, and null values are rejected. An explicit versioned envelope marker distinguishes documents from arbitrary `/kv` strings. Documents use the same ownership, replication, versions, tombstones, consistency levels, read repair, recovery, RocksDB WAL, and `sync=true` durability as ordinary values.

Only fields in `SHARDKV_INDEX_FIELDS` are indexed. Other valid document fields are stored but do not create index entries. Each physical replica maintains its own durable local index. A local record mutation reads the previous document and commits obsolete-index deletes, new-index puts, and the new `StoredRecord` in one RocksDB `WriteBatch`, so the record and indexes share one atomic WAL-backed update. Tombstones remove the prior document's entries. Replica application and read repair use this same local path; stale versions are rejected before they can downgrade an index.

Index keys use a reserved byte prefix beginning with `0xff` (which cannot begin a valid UTF-8 user key), followed by length-prefixed UTF-8 field data, a scalar type tag, deterministic scalar bytes, and a length-prefixed UTF-8 document key. Raw index keys are never exposed by the API.

## Distributed queries

`POST /query` accepts one or more equality filters. Multiple filters are ANDed by intersecting index candidate sets; ShardKV does not scan all records to evaluate predicates. Each physical node answers `/internal/query` from its local indexes and returns only keys for which it is the deterministic primary. This turns replicated physical data into one logical shard result and avoids replica duplicates; the coordinator also deduplicates defensively and sorts by key.

Fan-out uses a bounded Spring-managed executor and queries all nodes concurrently. A result is reported as complete only after every physical primary-shard node responds. If any required node is unavailable, the public query returns HTTP 503 rather than silently returning partial data. Point-read failover remains independent and can still succeed according to its requested consistency level.

The response is capped by `SHARDKV_QUERY_MAX_RESULTS`. Exceeding the cap is rejected explicitly instead of truncating a result that might be mistaken for complete.

## Failure detection

Each node periodically probes every other configured member through:

```http
GET /internal/health
```

The endpoint returns only the local node ID and `UP` status. Heartbeats run with fixed delay on a Spring-managed scheduler containing one thread, so executions do not overlap and the scheduler shuts down with the application.

Default state transitions are:

```text
HEALTHY
  -- first failed probe --> SUSPECT
  -- 3 consecutive failures --> UNHEALTHY

SUSPECT or UNHEALTHY
  -- 2 consecutive successes --> HEALTHY
```

A failed probe resets the recovery-success sequence. A successful recovery probe resets the failure sequence. The local node is always reported as `HEALTHY` by its own process.

This is a timeout-based detector, not a perfect distributed failure detector. Network delay or partitions can produce temporary false suspicions and different nodes can hold different health views. Real request results remain authoritative; health is used only to order or defer read attempts.

## Read behavior and failover

For replication factor `N`:

| Level | Required successful responses |
| --- | --- |
| `ONE` | `1` |
| `QUORUM` | `floor(N / 2) + 1` |
| `ALL` | `N` |

`ONE` prefers the primary while it is healthy. If the primary is unhealthy or an actual request to it fails, the coordinator tries assigned replicas in health order.

`QUORUM` gathers assigned-node responses and can succeed without the primary when enough replicas respond. `ALL` still requires every assigned node; a failed node causes HTTP 503.

Reads never contact nodes outside the key's deterministic replica set. A successful no-record response counts toward consistency, while a timeout or server failure does not. Reconciliation selects the highest version; a highest-version tombstone produces public HTTP 404.

## Read repair

QUORUM and ALL reads inspect all non-`UNHEALTHY` assigned nodes. After consistency is satisfied and the highest record is established, stale or missing successful responders receive the authoritative record:

```text
v5, v4, v5                  -> return v5; repair v4 to v5
tombstone v8, live v7, v8  -> return 404; repair live v7 with tombstone v8
missing, v3, v3            -> return v3; repair missing copy with v3
```

Replica application remains version-safe: newer incoming versions are applied, older versions are ignored, identical retries are idempotent, and contradictory content at the same version fails.

Repair currently runs synchronously after reconciliation. Repair failures are logged and counted but do not fail a read whose requested consistency was already satisfied. Unreachable replicas cannot be repaired until they respond again.

## Controlled recovery

The bounded internal endpoint:

```http
POST /internal/recovery/{key}
```

queries only that key's assigned replica set, requires a QUORUM of successful responses, selects the highest version, and repairs stale or missing successful copies. It does not scan RocksDB, route through the public API, recurse, generate versions, or change ownership.

Example:

```powershell
Invoke-RestMethod `
    -Method Post `
    -Uri "http://localhost:8081/internal/recovery/customer-42"
```

Read repair and controlled recovery both write through the direct replica endpoint, so repaired live values and tombstones use RocksDB's WAL and `sync=true` before acknowledgement.

## Write behavior

PUT and DELETE continue to route exactly once to the deterministic primary. The primary serializes same-key version generation with bounded striped locks, writes locally, and propagates the exact record to replicas.

If the primary is unavailable, PUT and DELETE fail for `ONE`, `QUORUM`, and `ALL`. A replica is never promoted automatically.

With an available primary and one unavailable replica at RF=3:

- `ONE` can succeed with the primary durable write.
- `QUORUM` can succeed with the primary and one replica.
- `ALL` fails.

There is no distributed rollback, so an unsuccessful consistency request can leave a partial mutation on nodes that acknowledged before the failure.

## Observability

Spring Boot Actuator exposes only the endpoints needed by this phase:

- `GET /actuator/health` for framework health.
- `GET /actuator/prometheus` for Prometheus exposition.
- `GET /health` remains the original ShardKV health contract.

HTTP request histograms are enabled so Prometheus can calculate P50, P95, and P99 latency with `histogram_quantile`; the application does not calculate percentiles itself. Every application meter receives stable `application=shardkv` and `node=<SHARDKV_NODE_ID>` tags.

Custom metrics use only bounded operation, outcome, consistency, peer, and health-state labels:

| Micrometer name | Prometheus series | Meaning |
| --- | --- | --- |
| `shardkv.storage.operations` | `shardkv_storage_operations_total` | Logical RocksDB get/put/delete success or failure |
| `shardkv.replication.operations` | `shardkv_replication_operations_total` | Replica put/delete/repair outcomes |
| `shardkv.consistency.operations` | `shardkv_consistency_operations_total` | Completed ONE/QUORUM/ALL get/put/delete outcomes |
| `shardkv.read.repair` | `shardkv_read_repair_total` | Repair attempts, successes, and failures |
| `shardkv.read.failover` | `shardkv_read_failover_total` | Reads satisfied without a primary response |
| `shardkv.heartbeat` | `shardkv_heartbeat_total` | Per-peer heartbeat outcomes |
| `shardkv.node.health` | `shardkv_node_health` | Current observed peer health |
| `shardkv.node.health.transitions` | `shardkv_node_health_transitions_total` | Bounded health-state transitions |
| `shardkv.query.operations` | `shardkv_query_operations_total` | Distributed-query outcomes |
| `shardkv.query.duration` | `shardkv_query_duration_seconds_*` | End-to-end query timer/histogram |
| `shardkv.query.results` | `shardkv_query_results_*` | Successful query result-count distribution |
| `shardkv.query.fanout.failures` | `shardkv_query_fanout_failures_total` | Failed local/remote query-shard calls |

The health gauge is numeric and Grafana-friendly: `HEALTHY=1`, `SUSPECT=0.5`, and `UNHEALTHY=0`. The `node` tag identifies the observing JVM and `peer` identifies the member being observed. Keys, document values, filter values, request bodies, and arbitrary error messages are never metric tags.

### Start Prometheus and Grafana

First run the three ShardKV JVMs as described below. Then start only the local observability services:

```powershell
docker compose -f observability\docker-compose.yml up -d
```

The pinned images are Prometheus `v3.14.0` and Grafana OSS `13.2.2`. Prometheus reaches host JVMs through `host.docker.internal` and scrapes ports 8081, 8082, and 8083 every five seconds.

Open:

- Prometheus targets: http://localhost:9090/targets
- Grafana: http://localhost:3000
- Dashboard: `ShardKV` folder -> `ShardKV Cluster Overview`

The development login defaults to `admin` / `admin`. Override it before first startup with `GRAFANA_ADMIN_USER` and `GRAFANA_ADMIN_PASSWORD`. The Prometheus datasource and dashboard are provisioned automatically; no UI setup is needed.

The dashboard contains cluster-health, request-rate, HTTP P50/P95/P99 latency, GET/PUT/DELETE, consistency outcomes, replication, heartbeat, health-transition, read-failover, read-repair, distributed-query, fan-out failure, result-count, and HTTP-error panels. These are operational measurements, not benchmark results.

Validate or stop the stack with:

```powershell
docker compose -f observability\docker-compose.yml config
docker compose -f observability\docker-compose.yml down
```

Named Docker volumes hold Prometheus and Grafana runtime state; neither runtime database is stored in the repository.

### Observability demonstrations

Generate ordinary traffic with the KV/document/query examples in this README and watch request, consistency, replication, and query panels change. To demonstrate failure detection, stop one node, wait for the configured failure threshold, and inspect the cluster-health, heartbeat, transition, consistency, and HTTP-error panels. Restarting the node shows its recovery after the configured success threshold.

For read repair, stop one replica, update a key with `QUORUM`, restart the stale replica, and perform a `QUORUM` read. The read-repair attempted/success counters will increase. Stopping a required node before `POST /query` produces HTTP 503 and increments both the query-failure and fan-out-failure series.

This compose setup is intended only for local development and demonstrations. It has no TLS, hardened credentials, retention tuning, access control, or production alerting.

## Configuration

| Environment variable | Default | Purpose |
| --- | --- | --- |
| `SERVER_PORT` | `8081` | HTTP and advertised node port |
| `SHARDKV_NODE_ID` | `node-1` | Stable local node ID |
| `SHARDKV_NODE_HOST` | `localhost` | Address advertised to peers |
| `SHARDKV_CLUSTER_MEMBERS` | three local nodes | Semicolon-delimited `id,host,port` entries |
| `SHARDKV_VIRTUAL_NODES` | `128` | Ring positions per physical node |
| `SHARDKV_REPLICATION_FACTOR` | `3` | Distinct physical copies per key |
| `SHARDKV_DEFAULT_CONSISTENCY` | `QUORUM` | Default `ONE`, `QUORUM`, or `ALL` |
| `SHARDKV_CONNECT_TIMEOUT` | `2s` | Peer connection timeout |
| `SHARDKV_READ_TIMEOUT` | `5s` | Peer response timeout |
| `SHARDKV_HEARTBEAT_INTERVAL` | `2s` | Delay between heartbeat rounds |
| `SHARDKV_FAILURE_THRESHOLD` | `3` | Consecutive failures before `UNHEALTHY` |
| `SHARDKV_RECOVERY_THRESHOLD` | `2` | Consecutive successes before `HEALTHY` recovery |
| `SHARDKV_DATA_DIR` | `./data` | This node's RocksDB directory |
| `SHARDKV_INDEX_FIELDS` | `city,role,experience,active` | Comma-delimited static indexed fields |
| `SHARDKV_QUERY_MAX_RESULTS` | `1000` | Maximum complete distributed query result |
| `SHARDKV_QUERY_PARALLELISM` | `8` | Bounded query fan-out worker count |
| `SHARDKV_QUERY_QUEUE_CAPACITY` | `100` | Bounded queued query-shard tasks |

Thresholds and durations must be positive. Each JVM must use a separate RocksDB directory.

## Run three nodes locally

Open three PowerShell terminals. All nodes must share membership, replication factor, consistency, and failure-detection settings.

Terminal 1:

```powershell
$env:SHARDKV_CLUSTER_MEMBERS = "node-1,localhost,8081;node-2,localhost,8082;node-3,localhost,8083"
$env:SHARDKV_REPLICATION_FACTOR = "3"
$env:SHARDKV_DEFAULT_CONSISTENCY = "QUORUM"
$env:SHARDKV_HEARTBEAT_INTERVAL = "2s"
$env:SHARDKV_FAILURE_THRESHOLD = "3"
$env:SHARDKV_RECOVERY_THRESHOLD = "2"
$env:SERVER_PORT = "8081"
$env:SHARDKV_NODE_ID = "node-1"
$env:SHARDKV_DATA_DIR = ".\data\node-1"
.\mvnw.cmd spring-boot:run
```

Terminal 2 uses port `8082`, node ID `node-2`, and `data\node-2`. Terminal 3 uses port `8083`, node ID `node-3`, and `data\node-3`; all other variables remain identical.

## API

Public endpoints:

| Method | Path | Behavior |
| --- | --- | --- |
| `GET` | `/health` | Local service status |
| `GET` | `/actuator/health` | Spring Boot health status |
| `GET` | `/actuator/prometheus` | Prometheus-format application metrics |
| `PUT` | `/kv/{key}?consistency=QUORUM` | Primary-coordinated versioned write |
| `GET` | `/kv/{key}?consistency=QUORUM` | Health-aware reconciled read and repair |
| `DELETE` | `/kv/{key}?consistency=ALL` | Primary-coordinated tombstone |
| `PUT` | `/documents/{key}?consistency=QUORUM` | Store/version/replicate a structured document |
| `GET` | `/documents/{key}?consistency=QUORUM` | Reconcile and decode a document |
| `DELETE` | `/documents/{key}?consistency=QUORUM` | Write a document tombstone and remove local indexes |
| `POST` | `/query` | Complete distributed equality/AND query |
| `GET` | `/cluster` | Membership and this node's health view |
| `GET` | `/cluster/owner/{key}` | Deterministic primary |
| `GET` | `/cluster/replicas/{key}` | Ordered placement |

Example cluster health inspection:

```powershell
(Invoke-RestMethod http://localhost:8081/cluster).members |
    Format-Table id, host, port, status
```

Example write and read:

```powershell
$body = @{ value = "phase-7-value" } | ConvertTo-Json

Invoke-RestMethod `
    -Method Put `
    -Uri "http://localhost:8081/kv/customer-42?consistency=ALL" `
    -ContentType "application/json" `
    -Body $body

Invoke-RestMethod "http://localhost:8082/kv/customer-42?consistency=QUORUM"
```

Document and query example:

```powershell
$document = @{
    fields = @{
        city = "Bengaluru"
        role = "SDE"
        experience = 1
        active = $true
    }
} | ConvertTo-Json

Invoke-RestMethod -Method Put `
    -Uri "http://localhost:8081/documents/user-101?consistency=QUORUM" `
    -ContentType "application/json" -Body $document

$query = @{ filters = @{ city = "Bengaluru"; role = "SDE" } } |
    ConvertTo-Json

Invoke-RestMethod -Method Post `
    -Uri "http://localhost:8082/query" `
    -ContentType "application/json" -Body $query
```

Internal endpoints are unauthenticated development interfaces and must not be exposed to untrusted networks:

- `GET /internal/health`
- `GET /internal/record/{key}`
- `PUT /internal/replica/record/{key}`
- `PUT|DELETE /internal/primary/kv/{key}`
- `POST /internal/recovery/{key}`
- `POST /internal/query?limit=1001`
- `POST /internal/index/contains/{key}` (bounded development inspection of one index membership)

## Durability and data compatibility

Primary writes, replica writes, tombstones, read repairs, and recovery repairs all use RocksDB's native WAL with `sync=true`. ShardKV does not implement a second application-level WAL.

Phase 3 raw string values are not automatically migrated to the versioned Phase 4/5 format. Use fresh development directories when upgrading from Phase 3 data.

## Test and package

```powershell
.\mvnw.cmd clean test
.\mvnw.cmd clean package
```

Run the packaged application with:

```powershell
java -jar target\shardkv-0.0.1-SNAPSHOT.jar
```

## Limitations

- Membership and ownership are static.
- Health observations are local, timeout-based hints and can disagree during partitions.
- No automatic write-primary failover or replica promotion exists.
- No dynamic membership, gossip, leader election, Raft, or other consensus exists.
- No hinted handoff or full background anti-entropy exists.
- Recovery is explicitly bounded to one requested key.
- Read repair is synchronous and only repairs nodes that successfully responded.
- Tombstones are retained indefinitely; garbage collection is not implemented.
- Failed writes can leave partial data; there is no distributed rollback.
- Membership changes do not trigger migration or rebalancing.
- Internal HTTP has no authentication or encryption.
- Secondary indexes support equality only, over a static configured field list.
- There is no SQL, full-text search, regex, range query, join, aggregation, sorting API, pagination, or runtime index-schema migration.
- Distributed queries require every physical primary-shard node; there is no query failover through replica indexes.
- Query result sizes are bounded and over-limit results fail rather than paginate.
- Prometheus and Grafana are local development infrastructure and are not production-secured.
- No SLO alerts, long-term metric retention, tracing, or centralized logging are configured.
- Dashboard observations are not load-test or benchmark claims.
- The system does not claim linearizability or complete partition tolerance.

## Roadmap

- Phase 1: Persistent storage + RocksDB WAL - complete
- Phase 2: Static membership + consistent hashing + request routing - complete
- Phase 3: Deterministic placement + synchronous durable replication - complete
- Phase 4: Versioned records + tombstones + configurable quorum consistency - complete
- Phase 5: Failure detection + read failover + read repair + scoped recovery - complete
- Phase 6: Secondary indexes + distributed queries - complete
- Phase 7: Actuator + Micrometer + Prometheus + Grafana observability - complete
- Phase 8: Scale/load testing
- Phase 9: Docker + CI/CD + production polish
