package com.incidentanalyst;

import io.javalin.Javalin;
import graphservice.GraphService;
import health.HealthMonitor;
import graphservice.orchestrator;
import queueservice.queue;
import faulttolerance.EventToFile;

import java.util.List;
import java.util.Map;

public class App {
    public static void main(String[] args) {
        HealthMonitor healthMonitor = new HealthMonitor();
        GraphService graphService = new GraphService(healthMonitor);
        queue eventQueue = new queue();
        EventToFile eventToFile = new EventToFile();
        orchestrator orch = new orchestrator(eventQueue, graphService, eventToFile, 4, healthMonitor);
        orch.start();

        Javalin app = Javalin.create()
            .get("/", ctx -> ctx.result("Incident Analyst Service is running"))
            .start(7070);

        // measure query latency
        app.before(ctx -> {
            ctx.attribute("startTime", System.nanoTime());
        });
        app.after(ctx -> {
            Long start = ctx.attribute("startTime");
            if(start != null){
                double ms = (System.nanoTime() - start) / 1_000_000.0;
                ctx.header("X-Response-Time-Ms", String.format("%.2f", ms));
                System.out.println(ctx.method() + " " + ctx.path() + " -> " + ctx.status() + " in " + String.format("%.2f", ms) + "ms");
            }
        });

        // GET /reachable?service=A
        app.get("/reachable", ctx -> {
            String service = ctx.queryParam("service");
            if(service == null){ ctx.status(400).json(Map.of("error", "missing 'service' param")); return; }
            List<String> result = orch.getReachable(service);
            ctx.json(Map.of("service", service, "reachable", result));
        });

        // GET /predecessors?service=A
        app.get("/predecessors", ctx -> {
            String service = ctx.queryParam("service");
            if(service == null){ ctx.status(400).json(Map.of("error", "missing 'service' param")); return; }
            List<String> result = orch.getPredecessors(service);
            ctx.json(Map.of("service", service, "predecessors", result));
        });

        // GET /cycles
        app.get("/cycles", ctx -> {
            List<List<String>> result = orch.getCycles();
            ctx.json(Map.of("cycles", result));
        });

        // GET /shortest_path?from=A&to=B
        app.get("/shortest_path", ctx -> {
            String from = ctx.queryParam("from");
            String to = ctx.queryParam("to");
            if(from == null || to == null){ ctx.status(400).json(Map.of("error", "missing 'from' or 'to' param")); return; }
            List<String> result = orch.getShortestPath(from, to);
            ctx.json(Map.of("from", from, "to", to, "path", result));
        });

        // GET /critical_services?k=5
        app.get("/critical_services", ctx -> {
            String kParam = ctx.queryParam("k");
            int k = (kParam != null) ? Integer.parseInt(kParam) : 5;
            List<String> result = orch.getCriticalNodes(k);
            ctx.json(Map.of("k", k, "critical_services", result));
        });

        // GET /health?from=A&to=B
        app.get("/health", ctx -> {
            String from = ctx.queryParam("from");
            String to = ctx.queryParam("to");
            if(from == null || to == null){ ctx.status(400).json(Map.of("error", "missing 'from' or 'to' param")); return; }
            ctx.json(orch.getHealth(from, to));
        });

        // POST /event — publish an event to the queue
        app.post("/event", ctx -> {
            common.Event event = ctx.bodyAsClass(common.Event.class);
            orch.publishEvent(event);
            ctx.json(Map.of("status", "queued", "eventId", event.getEventId()));
        });
    }
}
