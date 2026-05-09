package generator;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Random;
import java.util.UUID;

public class DatasetGenerator {
    private static final String OUTPUT_FILE = "events.csv";
    private static final String BASE_URL = "http://localhost:7070";
    private static final String[] STATUSES = {"OK", "OK", "OK", "OK", "ERROR", "TIMEOUT"};
    private static final String[] EVENT_TYPES = {"DEPENDENCY_OBSERVED", "DEPENDENCY_OBSERVED", "DEPENDENCY_OBSERVED", "DEPENDENCY_OBSERVED",
            "DEPENDENCY_OBSERVED", "DEPENDENCY_OBSERVED", "DEPENDENCY_OBSERVED", "DEPENDENCY_OBSERVED", "HEARTBEAT", "DEPENDENCY_REMOVED"};

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            printUsage();
            return;
        }

        String mode = args[0];
        switch (mode) {
            case "generate":
                if (args.length < 3) { printUsage(); return; }
                generate(Integer.parseInt(args[1]), Integer.parseInt(args[2]));
                break;
            case "produce":
                if (args.length < 4) { printUsage(); return; }
                produce(Integer.parseInt(args[1]), Integer.parseInt(args[2]), Integer.parseInt(args[3]));
                break;
            case "test":
                if (args.length < 3) { printUsage(); return; }
                testEndpoints(Integer.parseInt(args[1]), Integer.parseInt(args[2]));
                break;
            default:
                printUsage();
        }
    }

    private static void printUsage() {
        System.out.println("Usage:");
        System.out.println("  generate <numServices> <numEvents>                - Generate events.csv");
        System.out.println("  produce  <numServices> <numEvents> <numProducers> - POST events to running server");
        System.out.println("  test     <numServices> <numEvents>                - Benchmark all API endpoints");
    }

    private static void generate(int numServices, int numEvents) throws IOException {
        Random random = new Random(42);
        String[] services = new String[numServices];
        for (int i = 0; i < numServices; i++) {
            services[i] = "service-" + i;
        }

        try (BufferedWriter writer = new BufferedWriter(new FileWriter(OUTPUT_FILE))) {
            writer.write("eventId,eventType,fromService,toService,latency,timestamp,status");
            writer.newLine();

            long baseTime = System.currentTimeMillis();

            for (int i = 0; i < numEvents; i++) {
                String eventId = UUID.randomUUID().toString();
                String from = services[random.nextInt(numServices)];
                String to = services[random.nextInt(numServices)];
                while (to.equals(from)) {
                    to = services[random.nextInt(numServices)];
                }

                // 80% DEPENDENCY_OBSERVED, 10% HEARTBEAT, 10% DEPENDENCY_REMOVED
                int roll = random.nextInt(100);
                String eventType;
                if (roll < 80) {
                    eventType = "DEPENDENCY_OBSERVED";
                } else if (roll < 90) {
                    eventType = "HEARTBEAT";
                } else {
                    eventType = "DEPENDENCY_REMOVED";
                }

                int latency = 5 + random.nextInt(500);
                long timestamp = baseTime + (i * 10L);
                String status = STATUSES[random.nextInt(STATUSES.length)];

                writer.write(String.join(",",
                        eventId, eventType, from, to,
                        String.valueOf(latency), String.valueOf(timestamp), status));
                writer.newLine();
            }
        }

        System.out.println("Generated " + numEvents + " events for " + numServices + " services to " + OUTPUT_FILE);
    }

    private static void produce(int numServices, int numEvents, int numProducers) throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        String[] services = new String[numServices];
        for (int i = 0; i < numServices; i++) services[i] = "service-" + i;

        int eventsPerProducer = numEvents / numProducers;
        Thread[] threads = new Thread[numProducers];
        long startTime = System.nanoTime();

        for (int p = 0; p < numProducers; p++) {
            final int producerId = p;
            threads[p] = new Thread(() -> {
                Random random = new Random(producerId);
                int sent = 0;
                for (int i = 0; i < eventsPerProducer; i++) {
                    try {
                        String from = services[random.nextInt(numServices)];
                        String to = services[random.nextInt(numServices)];
                        while (to.equals(from)) to = services[random.nextInt(numServices)];

                        String eventType = EVENT_TYPES[random.nextInt(EVENT_TYPES.length)];
                        int latency = 5 + random.nextInt(500);
                        String status = STATUSES[random.nextInt(STATUSES.length)];

                        String json = """
                            {"eventId":"%s","eventType":"%s","fromService":"%s","toService":"%s","latency":%d,"timestamp":%d,"status":"%s"}
                            """.formatted(UUID.randomUUID(), eventType, from, to, latency, System.currentTimeMillis(), status).trim();

                        HttpRequest req = HttpRequest.newBuilder()
                                .uri(URI.create(BASE_URL + "/event"))
                                .header("Content-Type", "application/json")
                                .POST(HttpRequest.BodyPublishers.ofString(json))
                                .build();
                        client.send(req, HttpResponse.BodyHandlers.ofString());
                        sent++;
                    } catch (Exception e) {
                        System.err.println("Producer-" + producerId + " error: " + e.getMessage());
                    }
                }
                System.out.println("Producer-" + producerId + " finished: " + sent + " events sent");
            });
            threads[p].start();
        }

        for (Thread t : threads) t.join();

        double totalSec = (System.nanoTime() - startTime) / 1_000_000_000.0;
        System.out.printf("%nAll producers done. %d events in %.2fs (%.0f events/sec)%n",
                numEvents, totalSec, numEvents / totalSec);
    }

    private static void testEndpoints(int numServices, int numEvents) throws Exception {
        // delete old events file before starting
        File eventsFile = new File(OUTPUT_FILE);
        if(eventsFile.exists() && eventsFile.delete()){
            System.out.println("Deleted " + OUTPUT_FILE);
        }

        // produce events to the running server first
        System.out.println("=== Producing %d events for %d services ===\n".formatted(numEvents, numServices));
        produce(numServices, numEvents, 4);
        System.out.println();

        HttpClient client = HttpClient.newHttpClient();
        Random random = new Random();
        String svc1 = "service-" + random.nextInt(numServices);
        String svc2 = "service-" + random.nextInt(numServices);
        while (svc2.equals(svc1)) svc2 = "service-" + random.nextInt(numServices);

        System.out.println("=== API Benchmark (%d services, %d events) ===%n".formatted(numServices, numEvents));
        System.out.println("Using services: " + svc1 + ", " + svc2 + "\n");

        callAndPrint(client, "/reachable?service=" + svc1);
        callAndPrint(client, "/dependents?service=" + svc1);
        callAndPrint(client, "/shortest_path?from=" + svc1 + "&to=" + svc2);
        callAndPrint(client, "/cycles");
        callAndPrint(client, "/critical_services?k=5");
        callAndPrint(client, "/health?from=" + svc1 + "&to=" + svc2);

        System.out.println("\n=== Latency Benchmark (10 runs each) ===\n");

        String[] endpoints = {
            "/reachable?service=" + svc1,
            "/dependents?service=" + svc1,
            "/shortest_path?from=" + svc1 + "&to=" + svc2,
            "/cycles",
            "/critical_services?k=5",
            "/health?from=" + svc1 + "&to=" + svc2
        };

        for (String endpoint : endpoints) {
            double totalMs = 0;
            int runs = 10;
            for (int i = 0; i < runs; i++) {
                long start = System.nanoTime();
                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(BASE_URL + endpoint))
                        .GET().build();
                client.send(req, HttpResponse.BodyHandlers.ofString());
                totalMs += (System.nanoTime() - start) / 1_000_000.0;
            }
            System.out.printf("  %-50s avg: %.2fms%n", endpoint, totalMs / runs);
        }
    }

    private static void callAndPrint(HttpClient client, String endpoint) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + endpoint))
                .GET().build();

        long start = System.nanoTime();
        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        double ms = (System.nanoTime() - start) / 1_000_000.0;

        System.out.printf("[%d] %s (%.2fms)%n", resp.statusCode(), endpoint, ms);
        System.out.println("  " + resp.body());
        System.out.println();
    }
}
