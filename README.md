# Incident Analyst

A real-time service dependency graph analyzer built in Java. It ingests dependency events between microservices, builds a live directed graph, and exposes HTTP endpoints for graph queries like reachability, cycle detection, shortest path, and critical service identification.

## Architecture

```
                         ┌──────────────┐
  HTTP POST /event ────> │    Queue     │ ──> Worker Pool (4 threads) ──> GraphService
                         │ (Blocking Q) │                                    │
                         └──────────────┘                                    ├─ Graph (adjacency list)
                                                                             ├─ HealthMonitor (latency/error tracking)
  HTTP GET /reachable ──────────────────────────────────────────────────────> └─ EventToFile (backup)
  HTTP GET /cycles
  HTTP GET /shortest_path
  HTTP GET /critical_services
  ...
```

### Components

- **App** — Javalin HTTP server (port 7070), defines all REST endpoints.
- **orchestrator** — Bridges the queue, graph, health monitor, and file backup. Polls the queue every 50ms and dispatches events to a thread pool of 4 workers.
- **GraphService** — Maintains the dependency graph with a `ReentrantReadWriteLock`. All read queries operate on a snapshot (deep copy) of the graph.
- **Graph** — Directed graph stored as two adjacency maps (`nodes` for forward edges, `reverseNodes` for reverse edges).
- **HealthMonitor** — Tracks rolling average latency, P95 latency, error rates, and heartbeat staleness per service edge. Uses `ConcurrentHashMap` with per-deque synchronized blocks.
- **EventToFile** — Appends every processed event to `events.csv` for durability/replay.
- **queue** — A `LinkedBlockingQueue` (capacity 10,000) for decoupling event ingestion from processing.

### Event Types

| Type | Description |
|---|---|
| `DEPENDENCY_OBSERVED` | Records a latency observation between two services. Updates the graph edge and health metrics. |
| `DEPENDENCY_REMOVED` | Removes a service and all its edges from the graph. |
| `HEARTBEAT` | Records a heartbeat timestamp for a service (used for staleness detection). |

---

## Design Decisions and Tradeoffs

### Snapshot-based reads vs. locking every read

Read queries (reachable, cycles, shortest path, critical nodes) do **not** hold a lock while the algorithm runs. Instead, `GraphService.snapshot()` takes the read lock only long enough to deep-copy the graph, then releases it. The algorithm runs on the copy.

**Why:**
- Graph algorithms like Brandes' betweenness centrality or Kosaraju's SCC are expensive. Holding a read lock for the entire computation would block all writes (`addEvent`, `removeNode`) for the duration.
- With snapshots, writers are only blocked for the brief moment of the copy. Reads and writes can overlap — the read just operates on a slightly stale but **consistent** view.

**Tradeoff:** Each query allocates a full copy of the graph. This uses more memory but keeps write latency predictable.

### Locks for consistent snapshots vs. thread-safe data structures

I used a `ReentrantReadWriteLock` in `GraphService` rather than making `Graph` use `ConcurrentHashMap` / concurrent sets internally.

**Why:**
- `Graph.put()` does compound read-then-write operations (check if edge exists, update latency or add new node). These are not atomic even with `ConcurrentHashMap`.
- `snapshot()` copies both `nodes` and `reverseNodes`. With concurrent data structures alone, there is no guarantee that the snapshot sees a consistent state across both maps — a forward edge could be present without its corresponding reverse edge.
- The lock ensures the snapshot captures both maps at the same logical point in time.

**Tradeoff:** Reads are fully parallelized — multiple queries can snapshot and run concurrently since they only acquire the read lock. Writes are serialized, but this is acceptable because write throughput is bounded by the worker pool (4 threads) and the queue polling rate (50ms), not by lock contention.

### Single-file event backup

Every processed event is appended to `events.csv` via `EventToFile`. On startup, the graph is rebuilt by replaying this file.

**Why:** Simple crash recovery. If the process restarts, it replays all events and reconstructs the graph to its last known state. File writes happen in a separate thread so the caller (worker pool) is not blocked by disk I/O.

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
- **Read queries run concurrently** — multiple queries snapshot the graph in parallel (read lock allows concurrent access) and run their algorithms on independent copies.
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

---

## Data Generator

The generator is a standalone tool in `generator/DatasetGenerator.java` with three modes:

### 1. Generate a CSV dataset (offline)

Creates an `events.csv` file that the server replays on startup.

```bash
# Generate 1000 events across 20 services
javac generator/DatasetGenerator.java
java -cp . generator.DatasetGenerator generate 20 1000
```

### 2. Produce events to the running server (online)

POSTs events to `POST /event` using multiple concurrent producer threads.

```bash
# Send 5000 events across 50 services using 4 producer threads
java -cp . generator.DatasetGenerator produce 50 5000 4
```

### 3. Produce events + benchmark API endpoints

Produces events to the running server, then calls every query endpoint and reports latency (average over 10 runs).

```bash
# Produce 1000 events across 20 services, then benchmark all endpoints
java -cp . generator.DatasetGenerator test 20 1000
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

### GET /cycles — Detect circular dependencies (Kosaraju's SCC, two-pass DFS)

```bash
curl http://localhost:7070/cycles
```

**Response:**
```json
{"cycles": [["service-a", "service-b", "service-c"]]}
```

### GET /cycles_fast — Detect circular dependencies (Tarjan's SCC, single-pass DFS)

Same result as `/cycles` but uses Tarjan's algorithm — single DFS pass with better cache locality, ~1.5-2x faster in practice.

```bash
curl http://localhost:7070/cycles_fast
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
{"from": "api-gateway", "to": "db-service", "path": ["api-gateway", "user-service", "db-service"]}
```

### GET /critical_services — Top-k most critical services (Brandes' exact betweenness centrality)

Runs Dijkstra from **every** node. Exact but slow on large graphs — O(V × (E + V log V)).

```bash
curl "http://localhost:7070/critical_services?k=5"
```

**Response:**
```json
{"k": 5, "critical_services": ["user-service", "api-gateway", "auth-service", "db-service", "cache"]}
```

### GET /critical_services_fast — Top-k critical services (approximate Brandes)

Runs Dijkstra from a **random sample** of nodes instead of all V. ~95%+ accuracy at a fraction of the cost. The `samples` parameter controls the number of source nodes sampled (default 100).

```bash
curl "http://localhost:7070/critical_services_fast?k=5&samples=100"
```

**Response:**
```json
{"k": 5, "samples": 100, "critical_services": ["user-service", "api-gateway", "auth-service", "db-service", "cache"]}
```

### GET /health — Health metrics for an edge between two services

```bash
curl "http://localhost:7070/health?from=api-gateway&to=user-service"
```

**Response:**
```json
{
  "from": "api-gateway",
  "to": "user-service",
  "rolling_avg_latency": 42.5,
  "p95_latency": 120.0,
  "error_rate": 0.05,
  "from_stale": false,
  "to_stale": false,
  "from_last_heartbeat": 1715250000000,
  "to_last_heartbeat": 1715250000000
}
```

---

## Concurrency Model

| Component | Mechanism | Parallel? |
|---|---|---|
| HTTP requests | Jetty thread pool | Yes |
| Event processing | Worker pool (4 threads) | Yes |
| Graph writes (`addEvent`, `removeNode`) | Write lock | Serialized |
| Graph reads (all queries) | Read lock + snapshot | Yes, concurrent with each other |
| HealthMonitor | `ConcurrentHashMap` + per-deque `synchronized` | Yes |

---

## Project Structure

```
src/main/java/
  com/incidentanalyst/
    App.java              # Entry point, HTTP server, endpoint definitions
  graphservice/
    Graph.java            # Directed graph (adjacency list + reverse adjacency list)
    GraphService.java     # Thread-safe graph operations with snapshot reads
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
