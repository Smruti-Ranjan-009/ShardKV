# ShardKV

ShardKV is an incremental project for building a distributed key-value storage system. The long-term objective is to explore persistent storage, horizontal scaling, sharding, replication, consistency, fault tolerance, secondary indexing, distributed query execution, observability, and benchmarking without claiming features before they exist.

## Current status: Phase 2

ShardKV is now a statically configured, multi-node sharded key-value service. Phase 2 implements:

- Java 17, Spring Boot 3, and the Maven Wrapper
- persistent RocksDB storage with synchronous writes through RocksDB's native WAL
- static cluster membership and stable node identities
- deterministic SHA-256 consistent hashing
- configurable virtual nodes (128 per physical node by default)
- single-owner sharding and HTTP request forwarding
- cluster membership and key-owner inspection endpoints
- automated storage, hashing, routing, and API tests

ShardKV remains an early single-owner system. It does not yet implement replication, quorum consistency, automatic failover, failure detection, dynamic membership, data migration, or consensus.

## Architecture

```text
                         Client
                           |
                    any ShardKV node
                           |
                  KeyValueController
                           |
                       KeyRouter
                           |
                  ConsistentHashRing
                    /             \
              local owner      remote owner
                   |                |
          KeyValueService      HTTP NodeClient
                   |                |
          RocksDbKeyValueStore      +--> /internal/kv/{key}
                   |                         |
             RocksDB + WAL             owner RocksDB
```

Every node builds the same immutable hash ring from the same membership string. A public `/kv/{key}` request is executed locally when that node owns the key; otherwise it is forwarded to the owner's `/internal/kv/{key}` endpoint. Internal endpoints operate directly on local storage and never route again, preventing forwarding loops.

The ring hashes UTF-8 key bytes with SHA-256 and uses the first 64 bits as an unsigned ring position. Each physical node receives positions derived from `<node-id>#<virtual-node-index>`. Virtual nodes improve distribution but do not guarantee perfect uniformity.

## Prerequisites

- Java 17
- PowerShell on Windows

Maven does not need to be installed globally. All Maven commands use the checked-in wrapper.

## Configuration

| Environment variable | Default | Purpose |
| --- | --- | --- |
| `SERVER_PORT` | `8080` | HTTP port and advertised node port |
| `SHARDKV_NODE_ID` | `node-1` | Stable unique ID of this process |
| `SHARDKV_NODE_HOST` | `localhost` | Address advertised to peer nodes |
| `SHARDKV_CLUSTER_MEMBERS` | the local node | Semicolon-delimited `id,host,port` entries |
| `SHARDKV_VIRTUAL_NODES` | `128` | Ring positions per physical node |
| `SHARDKV_CONNECT_TIMEOUT` | `2s` | Peer HTTP connection timeout |
| `SHARDKV_READ_TIMEOUT` | `5s` | Peer HTTP read timeout |
| `SHARDKV_DATA_DIR` | `./data` | This node's RocksDB directory |

For three local nodes, all processes must use exactly this membership value:

```text
node-1,localhost,8081;node-2,localhost,8082;node-3,localhost,8083
```

Startup rejects an empty cluster, duplicate node IDs, invalid ports, invalid virtual-node counts, a missing local node, or a local endpoint that disagrees with its membership entry.

Each JVM must use a different RocksDB directory. Two processes must never open the same writable RocksDB directory simultaneously.

## Run one node

The defaults form a one-node cluster on port 8080:

```powershell
.\mvnw.cmd spring-boot:run
```

## Run three nodes locally

Open three PowerShell terminals in the repository. Use the same membership string in every terminal.

Terminal 1:

```powershell
$env:SHARDKV_CLUSTER_MEMBERS = "node-1,localhost,8081;node-2,localhost,8082;node-3,localhost,8083"
$env:SERVER_PORT = "8081"
$env:SHARDKV_NODE_ID = "node-1"
$env:SHARDKV_NODE_HOST = "localhost"
$env:SHARDKV_DATA_DIR = ".\data\node-1"
.\mvnw.cmd spring-boot:run
```

Terminal 2:

```powershell
$env:SHARDKV_CLUSTER_MEMBERS = "node-1,localhost,8081;node-2,localhost,8082;node-3,localhost,8083"
$env:SERVER_PORT = "8082"
$env:SHARDKV_NODE_ID = "node-2"
$env:SHARDKV_NODE_HOST = "localhost"
$env:SHARDKV_DATA_DIR = ".\data\node-2"
.\mvnw.cmd spring-boot:run
```

Terminal 3:

```powershell
$env:SHARDKV_CLUSTER_MEMBERS = "node-1,localhost,8081;node-2,localhost,8082;node-3,localhost,8083"
$env:SERVER_PORT = "8083"
$env:SHARDKV_NODE_ID = "node-3"
$env:SHARDKV_NODE_HOST = "localhost"
$env:SHARDKV_DATA_DIR = ".\data\node-3"
.\mvnw.cmd spring-boot:run
```

## API

| Method | Path | Result |
| --- | --- | --- |
| `GET` | `/health` | Service status |
| `PUT` | `/kv/{key}` | Routes to the owner and stores or replaces a value |
| `GET` | `/kv/{key}` | Routes to the owner; returns the value or `404` |
| `DELETE` | `/kv/{key}` | Routes to the owner, removes the value, and returns `204` |
| `GET` | `/cluster` | Local node, configured members, and virtual-node count |
| `GET` | `/cluster/owner/{key}` | Deterministic owner for a key |

The `/internal/kv/{key}` variants are reserved for node-to-node forwarding. They bypass routing and currently have no authentication; do not expose them to untrusted networks.

### Demonstrate routing through non-owner nodes

Find the owner from any node:

```powershell
Invoke-RestMethod http://localhost:8081/cluster/owner/test-key
```

Choose a different node for the write:

```powershell
$body = @{
    value = "distributed-value"
} | ConvertTo-Json

Invoke-RestMethod `
    -Method Put `
    -Uri "http://localhost:8081/kv/test-key" `
    -ContentType "application/json" `
    -Body $body
```

Read through another node; it independently calculates the same owner and forwards the request:

```powershell
Invoke-RestMethod http://localhost:8082/kv/test-key
```

If `node-1` happens to own `test-key`, use another port for the write so the request demonstrably enters through a non-owner.

## Durability and restart verification

Acknowledged writes use RocksDB `WriteOptions` with WAL enabled and synchronous writes. ShardKV does not implement a separate application-level WAL.

After writing a key, identify its owner with `/cluster/owner/{key}`. Stop only that owner, restart it with the same node configuration and data directory, then read through any cluster node:

```powershell
Invoke-RestMethod http://localhost:8082/kv/test-key
```

The value survives the owner restart because it is stored in that owner's RocksDB database. While the owner is offline, requests for its keys fail with `503 Service Unavailable`; Phase 2 does not reassign them.

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

- Each key has exactly one owner; there are no replicas.
- Membership is static and must be identical on every node.
- An unavailable owner makes its keys unavailable; there is no failover.
- Changing membership may change ownership, but automatic shard rebalancing and data migration are not implemented.
- Node-to-node HTTP is unauthenticated and unencrypted.
- There is no gossip, heartbeat failure detection, quorum consistency, or consensus.
- Durability depends on RocksDB and the guarantees of the local filesystem and storage device.

## Roadmap

- Phase 1: Persistent storage + RocksDB WAL — complete
- Phase 2: Static cluster membership + consistent hashing + request routing — complete
- Phase 3: Replication
- Phase 4: Quorum consistency
- Phase 5: Failure detection + recovery
- Phase 6: Secondary indexes + distributed queries
- Phase 7: Observability
- Phase 8: Scale/load testing
- Phase 9: Docker + CI/CD + production polish
