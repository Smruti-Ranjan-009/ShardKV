# ShardKV

ShardKV is an incremental project for building a distributed key-value storage system. Its long-term objective is to explore persistent storage, horizontal scaling, sharding, replication, consistency, fault tolerance, secondary indexing, distributed query execution, observability, and benchmarking without claiming features before they exist.

## Current status: Phase 3

ShardKV is a statically configured, multi-node key-value service with deterministic sharding and synchronous durable replication.

Implemented:

- Java 17, Spring Boot 3, and the Maven Wrapper
- persistent RocksDB storage with synchronous writes through RocksDB's native WAL
- static cluster membership and stable node identities
- deterministic SHA-256 consistent hashing with configurable virtual nodes
- primary and replica placement with a configurable replication factor
- public request routing to the primary
- synchronous PUT and DELETE replication to every assigned replica
- ALL-replicas acknowledgement semantics
- cluster membership, primary-owner, and replica-placement inspection endpoints
- automated storage, hashing, routing, replication, and API tests

Replication does not yet provide automatic failover. Reads use only the primary, and an unavailable primary makes its keys unavailable even when replica copies exist.

## Architecture

```text
                              Client
                                |
                         any ShardKV node
                                |
                           KeyRouter
                      local /          \ remote
                           /            \
             primary coordinator   primary node
                           \            /
                       ReplicaPlanner
                             |
             primary durable RocksDB write
                     /               \
          replica node 1          replica node 2
          RocksDB + WAL           RocksDB + WAL
```

Every node builds the same immutable hash ring from identical membership configuration. The first clockwise physical node is the primary. Replica placement continues clockwise through virtual-node positions while skipping repeated positions belonging to a physical node already selected.

For a replication factor of three, placement is ordered as:

```text
[primary, replica-1, replica-2]
```

The ring hashes UTF-8 input with SHA-256 and uses the first 64 bits as an unsigned position. Physical nodes receive positions derived from `<node-id>#<virtual-node-index>`. Virtual nodes improve distribution but do not guarantee perfect uniformity.

## Write and delete behavior

A public operation may enter through any node:

```text
client -> entry node -> calculated primary -> primary coordinator
```

Only the primary coordinates replication. It performs operations in this order:

1. Complete the primary's durable local RocksDB operation.
2. Send the operation synchronously to each replica in deterministic placement order.
3. Return success only after every replica acknowledges its durable local operation.

Replica endpoints write directly to local RocksDB and never route or replicate again. This prevents `primary -> replica -> replica` loops.

If a replica operation fails, the client receives `503 Service Unavailable`. Operations already completed on the primary or earlier replicas are not rolled back, so partial writes or deletes are possible. Phase 3 does not implement distributed transactions or quorum consistency.

GET continues to read only from the primary. It does not fall back to replicas when the primary is unavailable.

## Prerequisites

- Java 17
- PowerShell on Windows

Maven does not need to be installed globally. All Maven commands use the checked-in wrapper.

## Configuration

| Environment variable | Default | Purpose |
| --- | --- | --- |
| `SERVER_PORT` | `8081` | HTTP port and advertised node port |
| `SHARDKV_NODE_ID` | `node-1` | Stable unique ID of this process |
| `SHARDKV_NODE_HOST` | `localhost` | Address advertised to peer nodes |
| `SHARDKV_CLUSTER_MEMBERS` | three local development nodes | Semicolon-delimited `id,host,port` entries |
| `SHARDKV_VIRTUAL_NODES` | `128` | Ring positions per physical node |
| `SHARDKV_REPLICATION_FACTOR` | `3` | Number of distinct physical nodes storing each key |
| `SHARDKV_CONNECT_TIMEOUT` | `2s` | Peer HTTP connection timeout |
| `SHARDKV_READ_TIMEOUT` | `5s` | Peer HTTP read timeout |
| `SHARDKV_DATA_DIR` | `./data` | This node's RocksDB directory |

Replication factor must be at least one and cannot exceed the number of physical cluster members. A factor of one preserves Phase 2 single-owner behavior.

Startup also rejects empty membership, duplicate node IDs, invalid ports, invalid virtual-node counts, a missing local node, or a local endpoint that disagrees with its membership entry.

Every JVM must use a different RocksDB directory. Two processes must never open the same writable RocksDB directory simultaneously.

## Run three nodes locally

Open three PowerShell terminals in the repository. All processes must use exactly the same membership and replication factor.

Terminal 1:

```powershell
$env:SHARDKV_CLUSTER_MEMBERS = "node-1,localhost,8081;node-2,localhost,8082;node-3,localhost,8083"
$env:SHARDKV_REPLICATION_FACTOR = "3"
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
$env:SERVER_PORT = "8083"
$env:SHARDKV_NODE_ID = "node-3"
$env:SHARDKV_NODE_HOST = "localhost"
$env:SHARDKV_DATA_DIR = ".\data\node-3"
.\mvnw.cmd spring-boot:run
```

For a standalone development process, explicitly use a one-node membership and replication factor one:

```powershell
$env:SERVER_PORT = "8080"
$env:SHARDKV_NODE_ID = "node-1"
$env:SHARDKV_CLUSTER_MEMBERS = "node-1,localhost,8080"
$env:SHARDKV_REPLICATION_FACTOR = "1"
$env:SHARDKV_DATA_DIR = ".\data\standalone"
.\mvnw.cmd spring-boot:run
```

## API

| Method | Path | Result |
| --- | --- | --- |
| `GET` | `/health` | Service status |
| `PUT` | `/kv/{key}` | Routes to the primary and synchronously writes all assigned copies |
| `GET` | `/kv/{key}` | Routes to the primary; returns the value or `404` |
| `DELETE` | `/kv/{key}` | Routes to the primary and synchronously deletes all assigned copies |
| `GET` | `/cluster` | Local node, configured members, and virtual-node count |
| `GET` | `/cluster/owner/{key}` | Primary owner for a key |
| `GET` | `/cluster/replicas/{key}` | Ordered primary and replica placement |

Example placement inspection:

```powershell
Invoke-RestMethod http://localhost:8081/cluster/replicas/customer-42
```

Write through any node:

```powershell
$body = @{
    value = "replicated-value"
} | ConvertTo-Json

Invoke-RestMethod `
    -Method Put `
    -Uri "http://localhost:8081/kv/customer-42" `
    -ContentType "application/json" `
    -Body $body
```

Read through another node:

```powershell
Invoke-RestMethod http://localhost:8082/kv/customer-42
```

Internal endpoints are unauthenticated in Phase 3 and must not be exposed to untrusted networks:

- `/internal/primary/kv/{key}` coordinates PUT and DELETE only on the primary.
- `/internal/replica/kv/{key}` performs direct-local replica PUT and DELETE without further replication.
- `/internal/kv/{key}` retains direct-local access and is useful for inspecting physical copies during development.

## Durability and restart verification

Every primary and replica write uses RocksDB `WriteOptions` with WAL enabled and `sync=true`. ShardKV does not implement a separate application-level WAL.

After writing a replicated key, inspect each node directly:

```powershell
Invoke-RestMethod http://localhost:8081/internal/kv/customer-42
Invoke-RestMethod http://localhost:8082/internal/kv/customer-42
Invoke-RestMethod http://localhost:8083/internal/kv/customer-42
```

Stop every node, restart each with the same configuration and data directory, and repeat the reads. All assigned copies remain durable. With replication factor two, exactly the primary and one replica contain the key; the unassigned node returns `404`.

## Test and package

```powershell
.\mvnw.cmd clean test
.\mvnw.cmd clean package
```

The packaged application can also be run with:

```powershell
java -jar target\shardkv-0.0.1-SNAPSHOT.jar
```

## Limitations

- Membership is static and must be identical on every node.
- Replication is synchronous and currently requires every replica acknowledgement.
- There are no selectable ONE, QUORUM, or ALL consistency levels.
- Reads do not fail over to replicas when the primary is unavailable.
- There is no failure detector, hinted handoff, read repair, anti-entropy, or conflict reconciliation.
- There is no distributed transaction or rollback; replica failures can leave partial writes or deletes.
- Changing membership may change placement, but automatic data migration and rebalancing are not implemented.
- Node-to-node HTTP is unauthenticated and unencrypted.
- Replicas alone do not constitute automatic fault tolerance because failover is not implemented.

## Roadmap

- Phase 1: Persistent storage + RocksDB WAL — complete
- Phase 2: Static membership + consistent hashing + request routing — complete
- Phase 3: Deterministic placement + synchronous durable replication — complete
- Phase 4: Quorum consistency
- Phase 5: Failure detection + recovery
- Phase 6: Secondary indexes + distributed queries
- Phase 7: Observability
- Phase 8: Scale/load testing
- Phase 9: Docker + CI/CD + production polish
