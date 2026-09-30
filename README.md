# ShardKV

ShardKV is an incremental learning project for building a distributed key-value storage system. Its long-term objective is to demonstrate persistent storage, horizontal scaling, sharding, replication, consistency, fault tolerance, secondary indexing, distributed query execution, observability, and benchmarking without claiming those capabilities before they exist.

## Current status: Phase 1

ShardKV is currently a durable, single-node key-value service. Phase 1 provides:

- Java 17 and Spring Boot 3
- Maven Wrapper for builds without a global Maven installation
- a REST health endpoint
- persistent local storage backed by RocksDB
- UTF-8 keys and values
- synchronous writes through RocksDB's native write-ahead log (WAL)
- data that survives clean application restarts
- request validation and consistent HTTP status handling
- storage and API integration tests

ShardKV does not implement a separate application-level WAL. Durability is provided by RocksDB's native WAL and persistent storage engine. This phase does not provide sharding, replication, clustering, consensus, distributed storage, horizontal scaling, or fault tolerance.

## Current architecture

```text
HTTP controller
      |
Application service
      |
KeyValueStore interface
      |
RocksDbKeyValueStore
      |
RocksDB persistent files + native WAL
```

Controllers contain HTTP concerns, the service coordinates key-value operations, and `RocksDbKeyValueStore` owns RocksDB access and lifecycle. `KeyValueStore` keeps storage-specific details out of the API and service layers. The earlier `InMemoryKeyValueStore` remains available as a plain class for isolated use but is not a production Spring bean.

## Prerequisites

- Java 17
- PowerShell on Windows

Maven does not need to be installed globally. All commands use the checked-in Maven Wrapper.

## Run

```powershell
.\mvnw.cmd spring-boot:run
```

The service listens on port `8080` by default. Override it when needed:

```powershell
$env:SERVER_PORT = "9090"
.\mvnw.cmd spring-boot:run
```

RocksDB data is stored in `./data` by default. The directory is created automatically and can be overridden with a relative or absolute path suitable for the host platform:

```powershell
$env:SHARDKV_DATA_DIR = "C:\shardkv-data"
.\mvnw.cmd spring-boot:run
```

On Linux, the same setting can point to a path such as `/var/lib/shardkv`. Only one ShardKV process may open a writable RocksDB directory at a time.

## Test and package

```powershell
.\mvnw.cmd clean test
.\mvnw.cmd clean package
```

The packaged application can then be run with:

```powershell
java -jar target\shardkv-0.0.1-SNAPSHOT.jar
```

## API examples

Using `curl.exe` from PowerShell:

```powershell
curl.exe http://localhost:8080/health
curl.exe -X PUT http://localhost:8080/kv/example -H "Content-Type: application/json" -d '{"value":"hello"}'
curl.exe http://localhost:8080/kv/example
curl.exe -X DELETE http://localhost:8080/kv/example
```

Using native PowerShell commands:

```powershell
Invoke-RestMethod -Method Get -Uri http://localhost:8080/health
Invoke-RestMethod -Method Put -Uri http://localhost:8080/kv/example -ContentType application/json -Body '{"value":"hello"}'
Invoke-RestMethod -Method Get -Uri http://localhost:8080/kv/example
Invoke-WebRequest -Method Delete -Uri http://localhost:8080/kv/example
```

### Endpoints

| Method | Path | Result |
| --- | --- | --- |
| `GET` | `/health` | Service status |
| `PUT` | `/kv/{key}` | Stores or replaces a value |
| `GET` | `/kv/{key}` | Returns the value, or `404` if absent |
| `DELETE` | `/kv/{key}` | Removes the value and returns `204` |

PUT requests use this JSON shape:

```json
{
  "value": "hello"
}
```

## Verify restart persistence manually

Start the application:

```powershell
.\mvnw.cmd spring-boot:run
```

In another PowerShell window, write and read a value:

```powershell
$body = @{
    value = "persistent-value"
} | ConvertTo-Json

Invoke-RestMethod `
    -Method Put `
    -Uri "http://localhost:8080/kv/persist-test" `
    -ContentType "application/json" `
    -Body $body

Invoke-RestMethod http://localhost:8080/kv/persist-test
```

Stop the application completely with `Ctrl+C`, restart it with `.\mvnw.cmd spring-boot:run`, and read the key again:

```powershell
Invoke-RestMethod http://localhost:8080/kv/persist-test
```

The returned value remains `persistent-value` because the acknowledged write was synchronously recorded by RocksDB's WAL.

## Current limitations

- One local node and one writable process per data directory
- No replication, sharding, membership, failover, or distributed queries
- No authentication, authorization, transport security, backups, or operational observability yet
- Durability depends on the guarantees and correct operation of the local filesystem and storage device

## Roadmap

- Phase 1: Persistent storage + RocksDB WAL — complete
- Phase 2: Cluster membership + consistent hashing
- Phase 3: Replication
- Phase 4: Quorum consistency
- Phase 5: Failure detection + recovery
- Phase 6: Secondary indexes + distributed queries
- Phase 7: Observability
- Phase 8: Scale/load testing
- Phase 9: Docker + CI/CD + production polish
