# ShardKV

ShardKV is an incremental Java project for exploring the foundations of a distributed key-value store. Phase 5 adds timeout-based node health tracking, replica read failover, read repair, and scoped per-key recovery to the existing durable quorum-based cluster.

ShardKV remains a development system. It does not automatically promote write primaries and does not claim linearizability, consensus, or complete partition tolerance.

## Current status: Phase 5

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

Not implemented: automatic write-primary failover, dynamic membership, gossip, hinted handoff, full anti-entropy, consensus, automatic migration, or secondary indexes.

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

       HeartbeatMonitor ----> configured peer /internal/health
              |
       NodeHealthTracker
       HEALTHY / SUSPECT / UNHEALTHY
```

Every node builds the same immutable hash ring from identical static membership. SHA-256 hashes UTF-8 ring identifiers, and replica placement walks clockwise while skipping duplicate physical nodes.

The deterministic primary remains the only mutation coordinator and version generator. Health state never changes ownership or promotes a replica.

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
| `PUT` | `/kv/{key}?consistency=QUORUM` | Primary-coordinated versioned write |
| `GET` | `/kv/{key}?consistency=QUORUM` | Health-aware reconciled read and repair |
| `DELETE` | `/kv/{key}?consistency=ALL` | Primary-coordinated tombstone |
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
$body = @{ value = "phase-5-value" } | ConvertTo-Json

Invoke-RestMethod `
    -Method Put `
    -Uri "http://localhost:8081/kv/customer-42?consistency=ALL" `
    -ContentType "application/json" `
    -Body $body

Invoke-RestMethod "http://localhost:8082/kv/customer-42?consistency=QUORUM"
```

Internal endpoints are unauthenticated development interfaces and must not be exposed to untrusted networks:

- `GET /internal/health`
- `GET /internal/record/{key}`
- `PUT /internal/replica/record/{key}`
- `PUT|DELETE /internal/primary/kv/{key}`
- `POST /internal/recovery/{key}`

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
- The system does not claim linearizability or complete partition tolerance.

## Roadmap

- Phase 1: Persistent storage + RocksDB WAL — complete
- Phase 2: Static membership + consistent hashing + request routing — complete
- Phase 3: Deterministic placement + synchronous durable replication — complete
- Phase 4: Versioned records + tombstones + configurable quorum consistency — complete
- Phase 5: Failure detection + read failover + read repair + scoped recovery — complete
- Phase 6: Secondary indexes + distributed queries
- Phase 7: Observability
- Phase 8: Scale/load testing
- Phase 9: Docker + CI/CD + production polish
