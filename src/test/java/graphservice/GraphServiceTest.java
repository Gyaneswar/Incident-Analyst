package graphservice;

import common.Event;
import common.EventType;
import health.HealthMonitor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for graph-processing features: reachability, predecessors,
 * cycle detection, shortest path, critical services, and per-service health.
 *
 * Graph topology used in most tests:
 *
 *   A --10--> B --20--> C --30--> D
 *                        \         |
 *                         \--15--> E
 *                                  |
 *                                  v
 *                                  F
 *
 *   (edge weights = latency)
 */
public class GraphServiceTest {

    private HealthMonitor healthMonitor;
    private GraphService graphService;

    @BeforeEach
    void setUp() {
        healthMonitor = new HealthMonitor();
        graphService = new GraphService(healthMonitor, true);

        // Build: A->B(10), B->C(20), C->D(30), C->E(15), E->F(5)
        addEdge("A", "B", 10);
        addEdge("B", "C", 20);
        addEdge("C", "D", 30);
        addEdge("C", "E", 15);
        addEdge("E", "F", 5);
    }

    private void addEdge(String from, String to, int latency) {
        Event event = new Event();
        event.setEventId(UUID.randomUUID().toString());
        event.setEventType(EventType.DEPENDENCY_OBSERVED);
        event.setFromService(from);
        event.setToService(to);
        event.setLatency(latency);
        event.setTimestamp(System.currentTimeMillis());
        event.setStatus("OK");
        healthMonitor.addEvent(event);
        graphService.addEvent(new Node(latency, from), new Node(latency, to));
    }

    // ---- /reachable (BFS) ----

    @Test
    void reachableFromRoot_returnsAllNodes() {
        List<String> reachable = graphService.getReachable("A");
        assertTrue(reachable.containsAll(List.of("A", "B", "C", "D", "E", "F")));
        assertEquals(6, reachable.size());
    }

    @Test
    void reachableFromMid_excludesUpstream() {
        List<String> reachable = graphService.getReachable("C");
        assertTrue(reachable.containsAll(List.of("C", "D", "E", "F")));
        assertFalse(reachable.contains("A"));
        assertFalse(reachable.contains("B"));
    }

    @Test
    void reachableFromLeaf_returnsSelfOnly() {
        List<String> reachable = graphService.getReachable("D");
        assertEquals(List.of("D"), reachable);
    }

    @Test
    void reachableFromUnknown_returnsSelfOnly() {
        List<String> reachable = graphService.getReachable("Z");
        assertEquals(List.of("Z"), reachable);
    }

    // ---- /dependents (reverse BFS) ----

    @Test
    void predecessorsOfLeaf_returnsFullChain() {
        List<String> preds = graphService.predecessors("D");
        assertTrue(preds.containsAll(List.of("D", "C", "B", "A")));
    }

    @Test
    void predecessorsOfRoot_returnsSelfOnly() {
        List<String> preds = graphService.predecessors("A");
        assertEquals(List.of("A"), preds);
    }

    @Test
    void predecessorsOfF_includesEntireUpstream() {
        List<String> preds = graphService.predecessors("F");
        assertTrue(preds.containsAll(List.of("F", "E", "C", "B", "A")));
        assertFalse(preds.contains("D"));
    }

    // ---- /cycles (Tarjan's SCC) ----

    @Test
    void noCycles_inDAG() {
        List<List<String>> cycles = graphService.cyclesTarjan();
        assertTrue(cycles.isEmpty(), "DAG should have no cycles");
    }

    @Test
    void detectsCycle_whenPresent() {
        // Add D->A to create cycle A->B->C->D->A
        addEdge("D", "A", 5);
        List<List<String>> cycles = graphService.cyclesTarjan();
        assertFalse(cycles.isEmpty(), "Should detect at least one cycle");

        // Flatten all cycle members
        Set<String> cycleMembers = new HashSet<>();
        for (List<String> scc : cycles) {
            cycleMembers.addAll(scc);
        }
        assertTrue(cycleMembers.containsAll(List.of("A", "B", "C", "D")));
    }

    @Test
    void detectsSmallCycle() {
        // Add B->A to create A->B->A
        addEdge("B", "A", 5);
        List<List<String>> cycles = graphService.cyclesTarjan();
        assertFalse(cycles.isEmpty());

        boolean foundAB = cycles.stream().anyMatch(
                scc -> scc.contains("A") && scc.contains("B")
        );
        assertTrue(foundAB, "Should detect A<->B cycle");
    }

    // ---- /shortest_path (Dijkstra) ----

    @Test
    void shortestPath_directRoute() {
        Map<String, Object> result = graphService.shortestPath("A", "B");
        List<String> path = (List<String>) result.get("path");
        double latency = (double) result.get("latency");

        assertEquals(List.of("A", "B"), path);
        assertEquals(10.0, latency, 0.01);
    }

    @Test
    void shortestPath_multiHop() {
        Map<String, Object> result = graphService.shortestPath("A", "D");
        List<String> path = (List<String>) result.get("path");
        double latency = (double) result.get("latency");

        assertEquals(List.of("A", "B", "C", "D"), path);
        // A->B(10) + B->C(20) + C->D(30) = 60
        assertEquals(60.0, latency, 0.01);
    }

    @Test
    void shortestPath_choosesLowerLatency() {
        // Add a shortcut A->D with latency 50 (cheaper than A->B->C->D = 60)
        addEdge("A", "D", 50);
        Map<String, Object> result = graphService.shortestPath("A", "D");
        List<String> path = (List<String>) result.get("path");
        double latency = (double) result.get("latency");

        assertEquals(List.of("A", "D"), path);
        assertEquals(50.0, latency, 0.01);
    }

    @Test
    void shortestPath_toSelf() {
        Map<String, Object> result = graphService.shortestPath("A", "A");
        List<String> path = (List<String>) result.get("path");
        assertEquals(List.of("A"), path);
    }

    @Test
    void shortestPath_unreachable_returnsEmpty() {
        Map<String, Object> result = graphService.shortestPath("D", "A");
        List<String> path = (List<String>) result.get("path");
        assertTrue(path.isEmpty());
    }

    @Test
    void shortestPath_throughBranch() {
        Map<String, Object> result = graphService.shortestPath("A", "F");
        List<String> path = (List<String>) result.get("path");
        double latency = (double) result.get("latency");

        // A->B(10) + B->C(20) + C->E(15) + E->F(5) = 50
        assertEquals(List.of("A", "B", "C", "E", "F"), path);
        assertEquals(50.0, latency, 0.01);
    }

    // ---- /critical_services (approximate Brandes) ----

    @Test
    void criticalNodes_returnsTopK() {
        // C is the most critical — it's on the path to D, E, F
        List<String> critical = graphService.criticalNodesFast(2, 100);
        assertFalse(critical.isEmpty());
        assertEquals(List.of("C", "B"), critical);
        assertEquals(2, critical.size());
    }

    @Test
    void criticalNodes_hubNodeRanksHigh() {
        // Build an isolated star where H is clearly the hub
        HealthMonitor hm = new HealthMonitor();
        GraphService gs = new GraphService(hm, true);

        // S1->H->S2, S1->H->S3, S1->H->S4  (H is on every shortest path)
        Event e1 = makeEvent("S1", "H", 10);
        hm.addEvent(e1);
        gs.addEvent(new Node(10, "S1"), new Node(10, "H"));

        for (String spoke : List.of("S2", "S3", "S4", "S5")) {
            Event e = makeEvent("H", spoke, 10);
            hm.addEvent(e);
            gs.addEvent(new Node(10, "H"), new Node(10, spoke));
        }

        List<String> critical = gs.criticalNodesFast(2, 200);
        assertTrue(critical.contains("H"), "Hub node H should be critical, got: " + critical);
    }

    private Event makeEvent(String from, String to, int latency) {
        Event event = new Event();
        event.setEventId(UUID.randomUUID().toString());
        event.setEventType(EventType.DEPENDENCY_OBSERVED);
        event.setFromService(from);
        event.setToService(to);
        event.setLatency(latency);
        event.setTimestamp(System.currentTimeMillis());
        event.setStatus("OK");
        return event;
    }

    // ---- /health (per-service metrics) ----

    @Test
    void health_p95Latency_singleValue() {
        Map<String, Object> health = healthMonitor.getServiceHealth("B");
        double p95 = (double) health.get("p95_latency");
        // Only one event to B with latency 10
        assertEquals(10.0, p95, 0.01);
    }

    @Test
    void health_p95Latency_multipleValues() {
        // Add more events to B with varying latencies
        for (int i = 1; i <= 100; i++) {
            Event event = new Event();
            event.setEventId("p95-" + i);
            event.setEventType(EventType.DEPENDENCY_OBSERVED);
            event.setFromService("X");
            event.setToService("B");
            event.setLatency(i); // 1, 2, 3, ..., 100
            event.setTimestamp(System.currentTimeMillis());
            event.setStatus("OK");
            healthMonitor.addEvent(event);
        }
        double p95 = healthMonitor.calculateServiceP95Latency("B");
        // With values 10, 1..100 (101 values), P95 index = ceil(0.95*101)-1 = 96-1 = 95 → value ~96
        assertTrue(p95 >= 90, "P95 should be in the high range, got " + p95);
    }

    @Test
    void health_errorRate_allOK() {
        double errorRate = healthMonitor.calculateServiceErrorRate("B");
        assertEquals(0.0, errorRate, 0.01);
    }

    @Test
    void health_errorRate_withErrors() {
        // Add 4 OK + 1 ERROR events to a fresh service
        for (int i = 0; i < 4; i++) {
            Event event = new Event();
            event.setEventId("err-ok-" + i);
            event.setEventType(EventType.DEPENDENCY_OBSERVED);
            event.setFromService("X");
            event.setToService("ERR_SVC");
            event.setLatency(10);
            event.setTimestamp(System.currentTimeMillis());
            event.setStatus("OK");
            healthMonitor.addEvent(event);
        }
        Event errEvent = new Event();
        errEvent.setEventId("err-fail");
        errEvent.setEventType(EventType.DEPENDENCY_OBSERVED);
        errEvent.setFromService("X");
        errEvent.setToService("ERR_SVC");
        errEvent.setLatency(10);
        errEvent.setTimestamp(System.currentTimeMillis());
        errEvent.setStatus("ERROR");
        healthMonitor.addEvent(errEvent);

        double errorRate = healthMonitor.calculateServiceErrorRate("ERR_SVC");
        assertEquals(0.2, errorRate, 0.01); // 1 out of 5
    }

    @Test
    void health_unknownService_returnsDefaults() {
        Map<String, Object> health = healthMonitor.getServiceHealth("UNKNOWN");
        assertEquals(0.0, health.get("p95_latency"));
        assertEquals(0.0, health.get("error_rate"));
        assertEquals(true, health.get("stale"));
        assertEquals(-1L, health.get("last_heartbeat"));
    }

    // ---- Edge removal ----

    @Test
    void removeEdge_breaksPath() {
        graphService.removeEdge("B", "C");
        Map<String, Object> result = graphService.shortestPath("A", "D");
        List<String> path = (List<String>) result.get("path");
        assertTrue(path.isEmpty(), "Path A->D should be broken after removing B->C");
    }

    @Test
    void removeEdge_alternatePathStillWorks() {
        // Add A->C direct shortcut
        addEdge("A", "C", 25);
        graphService.removeEdge("B", "C");

        Map<String, Object> result = graphService.shortestPath("A", "D");
        List<String> path = (List<String>) result.get("path");
        assertEquals(List.of("A", "C", "D"), path);
    }
}
