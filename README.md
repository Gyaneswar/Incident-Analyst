# Incident Analyst

A real-time service dependency graph analyzer built in Java. It ingests dependency events between microservices, builds a live directed graph, and exposes HTTP endpoints for graph queries like reachability, cycle detection, shortest path, and critical service identification.

## Architecture
<img width="1321" height="828" alt="image" src="https://github.com/user-attachments/assets/6928ff89-26bf-4522-ac2d-c29bb8ffd005"/>


### Components

- **App** — Javalin HTTP server (port 7070), defines all REST endpoints.
- **orchestrator** — Bridges the queue, graph, health monitor, and file backup. Polls the queue every 50ms and dispatches events to a thread pool of 4 workers.
- **GraphService** — Maintains the dependency graph with a `ReentrantReadWriteLock`. Read queries hold the read lock and operate directly on the live graph (no snapshot/copy).
- **Graph** — Directed graph stored as two adjacency maps (`nodes` for forward edges, `reverseNodes` for reverse edges).
- **HealthMonitor** — Tracks per-service P95 latency, error rate, rolling average latency, and heartbeat staleness. Metrics are keyed by the destination service (`toService`). Uses `ConcurrentHashMap` with per-deque synchronized blocks.
- **EventToFile** — Appends every processed event to `events.csv` for durability/replay. Writes are serialized through a single-thread `ExecutorService` to prevent corruption and support graceful shutdown.
- **queue** — A `LinkedBlockingQueue` (capacity 10,000) for decoupling event ingestion from processing.

### Event Types

| Type | Description |
|---|---|
| `DEPENDENCY_OBSERVED` | Records a latency observation between two services. Updates the graph edge and health metrics. |
| `DEPENDENCY_REMOVED` | Removes the edge between `fromService` and `toService` from the graph. Both nodes remain with their other edges intact. |
| `HEARTBEAT` | Records a heartbeat timestamp for a service (used for staleness detection). |

---

## Algorithms

| Endpoint | Algorithm | Complexity | Description |
|---|---|---|---|
| `/reachable` | BFS | O(V + E) | Forward BFS from source to find all downstream services |
| `/dependents` | Reverse BFS | O(V + E) | BFS on the reverse adjacency list to find all upstream services |
| `/cycles` | Tarjan's SCC (iterative) | O(V + E) | Single-pass iterative DFS with low-link values. Finds all strongly connected components; cycles are SCCs with size > 1 |
| `/shortest_path` | Dijkstra's shortest path | O((V + E) log V) | Priority queue–based shortest path using edge latencies as weights. Early termination when target is reached |
| `/critical_services` | Approximate Brandes (betweenness centrality) | O(samples × (V + E) log V) | Dijkstra from a random sample of nodes (default 200), scores scaled by V/samples. ~95%+ accuracy vs exact Brandes at a fraction of the cost |

### Benchmark (10K services, 100K events)

| Endpoint | Avg Latency |
|---|-------------|
| `/reachable` | 12.64ms     |
| `/dependents` | 12.09ms     |
| `/shortest_path` | 5.56ms      |
| `/cycles` | 16.49ms     |
| `/critical_services` | 4026.45ms   |
| `/health` | 0.45ms      |

---

## Design Decisions and Tradeoffs

### Single-file event backup

Every processed event is appended to `events.csv` via `EventToFile`. On startup, the graph is rebuilt by replaying this file.

**Why:** Simple crash recovery. If the process restarts, it replays all events and reconstructs the graph to its last known state. File writes are submitted to a single-thread executor so the caller (worker pool) is not blocked by disk I/O, and writes are serialized to prevent file corruption.

**Tradeoff / future scaling:**
- Currently everything goes to a single file. Under high throughput this becomes a bottleneck (single writer, unbounded file growth).
- As I scale, this can be partitioned into **multiple segment files** (e.g., time-bucketed or size-bucketed), with older segments compacted or archived. This is analogous to how Kafka partitions its commit log.

### Single queue for mutations, direct dispatch for queries

There is currently one `LinkedBlockingQueue` for mutation events (`DEPENDENCY_OBSERVED`, `DEPENDENCY_REMOVED`, `HEARTBEAT`). Query endpoints (`/reachable`, `/cycles`, etc.) bypass the queue entirely and call the orchestrator directly.

**Why:** If queries went through the queue, I would need a mechanism to correlate each request with its response (e.g., a per-request `CompletableFuture` or a response map keyed by request ID). By dispatching queries directly to the orchestrator, I avoid this request-response tracking complexity entirely — the HTTP thread calls the method, gets the result, and returns it.

**Tradeoff / future scaling:**
- A single queue is a natural bottleneck as producer throughput grows.
- This can be scaled by introducing **multiple independent queues** (e.g., partitioned by service name or event type), each with its own consumer pool. This allows horizontal scaling of event processing while maintaining ordering guarantees within a partition.

### User-facing parallelism

From the user's perspective, the system appears fully parallelized:
- **`POST /event` returns instantly** — the event is pushed to the queue and the response comes back immediately. The actual graph mutation happens asynchronously in a background worker thread.
- **Read queries run concurrently** — multiple queries hold the read lock in parallel and operate on the live graph. No deep copy overhead.
- **Write serialization is invisible** — the write lock only affects the background worker threads. The user-facing HTTP layer never blocks on it.

---

## Prerequisites

- **Java 21+**
- **Maven 3.8+**

## Build and Run

```bash
mvn compile exec:java
```

The server starts on `http://localhost:7070`. On startup it replays any existing `events.csv` to rebuild the graph.

> If port 7070 is already occupied, the server will throw a bind error. You can change the port in `App.java` (`.start(7070)`).

## Tests

Unit tests for all graph-processing features are in `src/test/java/graphservice/GraphServiceTest.java` (JUnit 5, 25 tests).

```bash
mvn test -Dtest=graphservice.GraphServiceTest
```

| Category | Tests | What's verified |
|---|---|---|
| `/reachable` (BFS) | 4 | Forward reachability from root, mid-node, leaf, and unknown node |
| `/dependents` (Reverse BFS) | 3 | Upstream predecessors for leaf, root, and branch nodes |
| `/cycles` (Tarjan's SCC) | 3 | No cycles in DAG, large cycle detection, small cycle detection |
| `/shortest_path` (Dijkstra) | 6 | Direct hop, multi-hop, cheaper shortcut selection, self-path, unreachable, branch path — all with latency verification |
| `/critical_services` (Brandes) | 2 | Top-k count, hub node ranks high in star topology |
| `/health` (per-service) | 5 | P95 single/multi-value, error rate 0% and 20%, unknown service defaults |
| Edge removal | 2 | Path breaks after removal, alternate path still works |

---

## Data Generator

The generator is a standalone tool in `generator/DatasetGenerator.java` with three modes:

### 1. Generate a CSV dataset (offline)

Creates an `events.csv` file that the server replays on startup.

```bash
# Generate 1000 events across 20 services
javac generator/DatasetGenerator.java
java generator.DatasetGenerator generate 20 1000
```

### 2. Produce events to the running server (online)

POSTs events to `POST /event` using multiple concurrent producer threads.

```bash
# Send 5000 events across 50 services using 4 producer threads
java generator.DatasetGenerator produce 50 5000 4
```

### 3. Produce events + benchmark API endpoints

Produces events to the running server, then calls every query endpoint and reports latency (average over 10 runs).

```bash
# Produce 1000 events across 20 services, then benchmark all endpoints
java generator.DatasetGenerator test 20 1000
```

---

## API Endpoints

### Health check

```bash
curl http://localhost:7070/
```

### POST /event — Publish an event

```bash
curl -X POST http://localhost:7070/event \
  -H "Content-Type: application/json" \
  -d '{
    "eventId": "evt-001",
    "eventType": "DEPENDENCY_OBSERVED",
    "fromService": "api-gateway",
    "toService": "user-service",
    "latency": 45,
    "timestamp": 1715250000000,
    "status": "OK"
  }'
```

**Response:**
```json
{"status": "queued", "eventId": "evt-001"}
```

### GET /reachable — All downstream services reachable from a service (BFS)

```bash
curl "http://localhost:7070/reachable?service=api-gateway"
```

**Response:**
```json
{"service": "api-gateway", "reachable": ["api-gateway", "user-service", "db-service"]}
```

### GET /dependents — All upstream services that transitively depend on a service

```bash
curl "http://localhost:7070/dependents?service=db-service"
```

**Response:**
```json
{"service": "db-service", "dependents": ["db-service", "user-service", "api-gateway"]}
```

### GET /cycles — Detect circular dependencies (Tarjan's SCC)

Uses Tarjan's algorithm — iterative single-pass DFS with low-link values. Finds all strongly connected components with size > 1 (cycles). The old Kosaraju's two-pass DFS is still available in `GraphService.cycles()` but commented out in `App.java`.

```bash
curl http://localhost:7070/cycles
```

**Response:**
```json
{"cycles": [["service-a", "service-b", "service-c"]]}
```

### GET /shortest_path — Shortest (lowest latency) path between two services (Dijkstra)

```bash
curl "http://localhost:7070/shortest_path?from=api-gateway&to=db-service"
```

**Response:**
```json
{"from": "api-gateway", "to": "db-service", "path": ["api-gateway", "user-service", "db-service"], "latency": 87.5}
```

### GET /critical_services — Top-k critical services (approximate Brandes)

Runs Dijkstra from a **random sample** of nodes instead of all V, then scales scores by `V/samples`. The `samples` parameter controls the number of source nodes sampled (default 200). The old exact Brandes (Dijkstra from every node) is still available in `GraphService.criticalNodes()` but commented out in `App.java`.

```bash
curl "http://localhost:7070/critical_services?k=5&samples=200"
```

**Response:**
```json
{"k": 5, "samples": 200, "critical_services": ["user-service", "api-gateway", "auth-service", "db-service", "cache"]}
```

### GET /health — Per-service health metrics (P95 latency, error rate)

Returns P95 latency and error rate for a specific service. Metrics are aggregated from all incoming edges (any `fromService -> service` event records latency/status under the destination service).

```bash
curl "http://localhost:7070/health?service=user-service"
```

**Response:**
```json
{
  "service": "user-service",
  "p95_latency": 120.0,
  "error_rate": 0.05,
  "stale": false,
  "last_heartbeat": 1715250000000
}
```

---

## Graceful Shutdown

On `SIGTERM` (or normal JVM termination), a shutdown hook runs in order:

1. **Javalin** stops — finishes in-flight HTTP requests, stops accepting new ones.
2. **Orchestrator** shuts down — stops the poller, drains the worker pool (5s timeout).
3. **EventToFile** shuts down — flushes pending file writes (5s timeout).

---

## Concurrency Model

| Component | Mechanism                                      | Parallel?                       |
|---|------------------------------------------------|---------------------------------|
| HTTP requests | Jetty thread pool                              | Yes                             |
| Event processing | Worker pool (10 threads)                        | Yes                             |
| Graph writes (`addEvent`, `removeNode`) | Write lock                                     | yes, requests go to the queue   |
| Graph reads (all queries) | Read lock on live graph                        | Yes, concurrent with each other |
| HealthMonitor | `ConcurrentHashMap` + per-deque `synchronized` | Yes                             |
| EventToFile writes | Single-thread `ExecutorService`                | Serialized (no corruption)      |

---

## Project Structure

```
src/main/java/
  com/incidentanalyst/
    App.java              # Entry point, HTTP server, endpoint definitions
  graphservice/
    Graph.java            # Directed graph (adjacency list + reverse adjacency list)
    GraphService.java     # Thread-safe graph operations with read-lock reads
    Node.java             # Graph node (service name + latency)
    orchestrator.java     # Event dispatcher: queue -> workers -> graph/health/file
  health/
    HealthMonitor.java    # Rolling latency, P95, error rate, heartbeat tracking
  queueservice/
    queue.java            # LinkedBlockingQueue wrapper (capacity 10,000)
  faulttolerance/
    EventToFile.java      # CSV append-log for event durability and replay
  common/
    Event.java            # Event POJO
    EventType.java        # DEPENDENCY_OBSERVED | DEPENDENCY_REMOVED | HEARTBEAT
generator/
  DatasetGenerator.java   # Offline CSV generation, online event producer, API benchmark
```
