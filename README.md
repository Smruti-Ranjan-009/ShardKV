# ShardKV

ShardKV is an incremental Java project for exploring the foundations of a distributed key-value store. Phase 4 adds configurable quorum-based consistency to the existing durable, statically configured cluster. It is still a development system, not a production database.

## Current status: Phase 4

Implemented:

- Java 17, Spring Boot 3, and the Maven Wrapper
- RocksDB storage with native WAL enabled and `sync=true`
- static cluster membership and stable node identities
- deterministic SHA-256 consistent hashing with configurable virtual nodes
- deterministic primary and replica placement
- configurable replication factor
- `ONE`, `QUORUM`, and `ALL` consistency for PUT, GET, and DELETE
- primary-generated record versions and durable tombstones
- highest-version reconciliation during distributed reads
- cluster, owner, and replica-placement inspection endpoints

Not implemented: dynamic membership, failure detection, automatic primary failover, read repair, hinted handoff, anti-entropy, tombstone garbage collection, consensus, or automatic data migration.

## Architecture

```text
                              Client
                                |
                         any ShardKV node
                                |
                            KeyRouter
                     writes /          \ reads
                           /            \
               deterministic primary   assigned replica set
                         |              /       |       \
               version + tombstone   primary  replica  replica
                         |              \       |       /
                  durable local write    highest-version
                         |                reconciliation
                 replica propagation
                    /           \
             RocksDB + WAL   RocksDB + WAL
```

Every node constructs the same immutable hash ring from identical membership configuration. UTF-8 ring identifiers are hashed with SHA-256; the first 64 bits form an unsigned ring position. Each physical node receives positions derived from `<node-id>#<virtual-node-index>`. Replica placement walks clockwise from the primary and skips repeated virtual nodes belonging to an already selected physical node.

## Consistency model

For replication factor `N`, the required successful node responses are:

| Level | Required responses |
| --- | --- |
| `ONE` | `1` |
| `QUORUM` | `floor(N / 2) + 1` |
| `ALL` | `N` |

The primary's durable local mutation counts as one write acknowledgement. The default is `QUORUM` and can be overridden globally or per request.

For PUT and DELETE, a request entering a non-primary is forwarded once to the primary. The primary serializes mutations for that key with one of 256 bounded striped locks, reads its current local version, increments it, durably writes the new record, and sends that exact record to every assigned replica. The lock covers version generation and the primary write; replica propagation follows after the local mutation is established. There is no distributed lock.

Replica calls are currently attempted synchronously in deterministic order. The coordinator waits for every attempted call to finish and then evaluates the requested threshold. This implements the acknowledgement semantics but does not yet optimize ONE or QUORUM latency by returning early.

Reads may be coordinated by any node. The coordinator requests metadata from every assigned node, counts a successful "no record" response as a response, and distinguishes it from transport or server failure. It returns the contents of the highest-version record once the requested response count is available. A highest-version tombstone is exposed to the public client as `404 Not Found`.

For replication factor `N`, read and write sets intersect when `R + W > N`. For example, RF=3 with QUORUM reads and writes uses `R=2` and `W=2`. This provides configurable quorum-based consistency under the system's stated assumptions; ShardKV does not claim linearizability or consensus.

## Versioned records and deletes

RocksDB values are JSON-encoded records with this internal shape:

```json
{
  "value": "hello",
  "version": 7,
  "tombstone": false
}
```

A deletion stores a newer record rather than physically deleting the key:

```json
{
  "value": null,
  "version": 8,
  "tombstone": true
}
```

Only the deterministic primary generates versions. The first mutation is version 1; every later PUT or DELETE increments the primary's current version. A PUT after a tombstone creates a newer live record.

Replicas apply newer versions, accept identical same-version retries as idempotent, and ignore older versions. Contradictory records at the same version fail as an invariant violation. Tombstones prevent an older live replica from winning a quorum read after it missed a delete. Tombstones are not garbage-collected in Phase 4.

Phase 3 databases stored raw string values and are not automatically migrated. Delete existing development data directories before first running Phase 4, or use new directories.

## Prerequisites

- Java 17
- PowerShell on Windows

Maven does not need to be installed globally. Use the checked-in Maven Wrapper for every build command.

## Configuration

| Environment variable | Default | Purpose |
| --- | --- | --- |
| `SERVER_PORT` | `8081` | HTTP port and advertised node port |
| `SHARDKV_NODE_ID` | `node-1` | Stable unique ID for this process |
| `SHARDKV_NODE_HOST` | `localhost` | Address advertised to peers |
| `SHARDKV_CLUSTER_MEMBERS` | three local nodes | Semicolon-delimited `id,host,port` entries |
| `SHARDKV_VIRTUAL_NODES` | `128` | Ring positions per physical node |
| `SHARDKV_REPLICATION_FACTOR` | `3` | Distinct physical copies per key |
| `SHARDKV_DEFAULT_CONSISTENCY` | `QUORUM` | Default `ONE`, `QUORUM`, or `ALL` |
| `SHARDKV_CONNECT_TIMEOUT` | `2s` | Peer HTTP connection timeout |
| `SHARDKV_READ_TIMEOUT` | `5s` | Peer HTTP response timeout |
| `SHARDKV_DATA_DIR` | `./data` | This node's RocksDB directory |

Replication factor must be at least one and cannot exceed the physical member count. Every JVM must use a distinct RocksDB directory; two processes must never open the same writable directory simultaneously. Startup also rejects empty membership, duplicate IDs, invalid ports, invalid virtual-node counts, or a missing/mismatched local node.

## Run three nodes locally

Open three PowerShell terminals in the repository. All nodes must use identical membership, replication factor, and default consistency.

Terminal 1:

```powershell
$env:SHARDKV_CLUSTER_MEMBERS = "node-1,localhost,8081;node-2,localhost,8082;node-3,localhost,8083"
$env:SHARDKV_REPLICATION_FACTOR = "3"
$env:SHARDKV_DEFAULT_CONSISTENCY = "QUORUM"
$env:SERVER_PORT = "8081"
$env:SHARDKV_NODE_ID = "node-1"
$env:SHARDKV_NODE_HOST = "localhost"
$env:SHARDKV_DATA_DIR = ".\data\node-1"
.\mvnw.cmd spring-boot:run
```

Terminal 2:

```powershell
$env:SHARDKV_CLUSTER_MEMBERS = "node-1,localhost,8081;node-2,localhost,8082;node-3,localhost,8083"
$env:SHARDKV_REPLICATION_FACTOR = "3"
$env:SHARDKV_DEFAULT_CONSISTENCY = "QUORUM"
$env:SERVER_PORT = "8082"
$env:SHARDKV_NODE_ID = "node-2"
$env:SHARDKV_NODE_HOST = "localhost"
$env:SHARDKV_DATA_DIR = ".\data\node-2"
.\mvnw.cmd spring-boot:run
```

Terminal 3:

```powershell
$env:SHARDKV_CLUSTER_MEMBERS = "node-1,localhost,8081;node-2,localhost,8082;node-3,localhost,8083"
$env:SHARDKV_REPLICATION_FACTOR = "3"
$env:SHARDKV_DEFAULT_CONSISTENCY = "QUORUM"
$env:SERVER_PORT = "8083"
$env:SHARDKV_NODE_ID = "node-3"
$env:SHARDKV_NODE_HOST = "localhost"
$env:SHARDKV_DATA_DIR = ".\data\node-3"
.\mvnw.cmd spring-boot:run
```

For a standalone process, set a one-node membership and replication factor one. All three consistency levels then require one response.

## Public API

| Method | Path | Behavior |
| --- | --- | --- |
| `GET` | `/health` | Service status |
| `PUT` | `/kv/{key}?consistency=QUORUM` | Creates a new version and replicates it |
| `GET` | `/kv/{key}?consistency=QUORUM` | Reconciles assigned copies by version |
| `DELETE` | `/kv/{key}?consistency=ALL` | Creates and replicates a tombstone |
| `GET` | `/cluster` | Local node and configured members |
| `GET` | `/cluster/owner/{key}` | Primary owner |
| `GET` | `/cluster/replicas/{key}` | Ordered primary and replicas |

The `consistency` query parameter is optional and case-sensitive; omitting it uses `SHARDKV_DEFAULT_CONSISTENCY`. An unsupported value returns HTTP 400.

```powershell
$body = @{ value = "quorum-value" } | ConvertTo-Json

Invoke-RestMethod `
    -Method Put `
    -Uri "http://localhost:8081/kv/customer-42?consistency=QUORUM" `
    -ContentType "application/json" `
    -Body $body

Invoke-RestMethod "http://localhost:8082/kv/customer-42?consistency=QUORUM"

Invoke-RestMethod `
    -Method Delete `
    -Uri "http://localhost:8083/kv/customer-42?consistency=ALL"
```

## Internal API and inspection

Internal endpoints bypass public routing and must not be exposed to untrusted networks:

- `PUT` or `DELETE /internal/primary/kv/{key}?consistency=...` executes only on the primary and coordinates replication.
- `PUT /internal/replica/record/{key}` applies a complete versioned record locally and never forwards or replicates.
- `GET /internal/record/{key}` returns the local complete record, including tombstone metadata, or 404 for no record.
- `GET /internal/kv/{key}` returns only a local live value and treats tombstones as 404.

Inspect physical copies during development:

```powershell
Invoke-RestMethod http://localhost:8081/internal/record/customer-42
Invoke-RestMethod http://localhost:8082/internal/record/customer-42
Invoke-RestMethod http://localhost:8083/internal/record/customer-42
```

The internal HTTP API is unauthenticated and unencrypted in this phase.

## Failure and stale-replica behavior

With RF=3, stop one replica while leaving the primary and the other replica running:

- `PUT ...?consistency=ALL` fails with HTTP 503.
- `PUT ...?consistency=QUORUM` can succeed with two durable acknowledgements.
- `PUT ...?consistency=ONE` can succeed after the primary durable write.

All assigned replicas are still attempted. A failed consistency request can therefore leave a partial mutation on nodes that already acknowledged; there is no distributed rollback.

After a missed update, a restarted replica can remain stale. A QUORUM read compares successful responses and returns the newest record but deliberately does not repair the stale copy. The same rule applies to deletion: a newer tombstone wins over an older live value and produces public HTTP 404.

PUT and DELETE have no primary failover. If the primary is unavailable, mutation requests fail. GET can use any successful assigned responses, including replicas, if its requested threshold is met; failures are discovered from actual HTTP attempts rather than a failure detector.

## Durability

Every local primary or replica mutation uses RocksDB's native WAL with `sync=true` before acknowledging. ShardKV does not implement a redundant application-level WAL. Versions, live values, and tombstones survive clean process restart when the same node-specific data directories are reused.

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

- Membership is static and must be identical on every node.
- Mutations are coordinated only by the deterministic primary; there is no automatic primary failover.
- Replication calls are synchronous and currently attempted sequentially.
- No failure detector or heartbeat subsystem exists.
- No read repair, hinted handoff, anti-entropy, or stale-copy recovery exists.
- Tombstones are retained indefinitely; garbage collection is not implemented.
- No distributed transaction or rollback exists, so failed operations can leave partial data.
- Changing membership can change placement, but there is no data migration or rebalancing.
- Internal traffic has no authentication or encryption.
- There is no gossip, Raft, leader election, or other consensus protocol.
- Phase 3 raw-value RocksDB files are not migrated automatically.
- Configurable quorums do not by themselves imply linearizability or full fault tolerance.

## Roadmap

- Phase 1: Persistent storage + RocksDB WAL — complete
- Phase 2: Static membership + consistent hashing + request routing — complete
- Phase 3: Deterministic placement + synchronous durable replication — complete
- Phase 4: Versioned records + tombstones + configurable quorum consistency — complete
- Phase 5: Failure detection + recovery
- Phase 6: Secondary indexes + distributed queries
- Phase 7: Observability
- Phase 8: Scale/load testing
- Phase 9: Docker + CI/CD + production polish
