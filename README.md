# ShardKV

ShardKV is an incremental learning project for building a distributed key-value storage system. Its long-term objective is to demonstrate persistent storage, horizontal scaling, sharding, replication, consistency, fault tolerance, secondary indexing, distributed query execution, observability, and benchmarking without claiming those capabilities before they exist.

## Current status: Phase 0

The current application provides only a production-quality Java project foundation:

- Java 17 and Spring Boot 3
- Maven Wrapper for builds without a global Maven installation
- a REST health endpoint
- a temporary, thread-safe in-memory key-value store
- request validation and consistent HTTP status handling
- integration tests for the exposed behavior

Data is held only in memory and is lost whenever the application stops. Sharding, replication, persistence, clustering, and networking between nodes are not implemented.

## Current architecture

```text
HTTP controller
      |
Application service
      |
KeyValueStore interface
      |
ConcurrentHashMap implementation
```

The `KeyValueStore` interface is the boundary for a future persistent implementation. Controllers contain HTTP concerns, the service coordinates key-value operations, and the storage implementation owns data access.

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

## Roadmap

- Phase 1: Persistent storage + WAL
- Phase 2: Cluster membership + consistent hashing
- Phase 3: Replication
- Phase 4: Quorum consistency
- Phase 5: Failure detection + recovery
- Phase 6: Secondary indexes + distributed queries
- Phase 7: Observability
- Phase 8: Scale/load testing
- Phase 9: Docker + CI/CD + production polish
