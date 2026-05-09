package graphservice;

import common.*;
import health.HealthMonitor;
import queueservice.queue;
import faulttolerance.EventToFile;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

//This class will read the events from the queue service and process them
public class orchestrator {
    private final queue eventQueue;
    private final GraphService graphService;
    private final EventToFile eventToFile;
    private final HealthMonitor healthMonitor;
    private final ScheduledExecutorService poller;
    private final ExecutorService workerPool;
    private volatile boolean running = true;

    private final int pollerThreads;

    public orchestrator(queue eventQueue, GraphService graphService, EventToFile eventToFile, int workerThreads, HealthMonitor healthMonitor){
        this.eventQueue = eventQueue;
        this.graphService = graphService;
        this.eventToFile = eventToFile;
        this.pollerThreads = Math.max(1, workerThreads / 2);
        this.poller = Executors.newScheduledThreadPool(pollerThreads);
        this.workerPool = Executors.newFixedThreadPool(workerThreads);
        this.healthMonitor = healthMonitor;
    }

    public void start(){
        for(int i = 0; i < pollerThreads; i++){
            poller.scheduleAtFixedRate(this::pollQueue, 0, 50, TimeUnit.MILLISECONDS);
        }
    }

    private void pollQueue(){
        while(running){
            Event event = eventQueue.consume();
            if(event == null) break;
            workerPool.submit(() -> processEvent(event));
        }
    }

    private void processEvent(Event event){
        eventToFile.writeEvents(event);

        switch(event.getEventType()){
            case DEPENDENCY_OBSERVED:
                Node from = new Node(event.getLatency(), event.getFromService());
                Node to = new Node(event.getLatency(), event.getToService());
                healthMonitor.addEvent(event);
                graphService.addEvent(from, to);
                break;
            case DEPENDENCY_REMOVED:
                graphService.removeEdge(event.getFromService(), event.getToService());
                break;
            case HEARTBEAT:
                healthMonitor.recordHeartbeat(event);
                break;
        }
    }

    // --- Query delegation ---

    public void publishEvent(Event event) throws InterruptedException {
        eventQueue.publish(event);
    }

    public List<String> getReachable(String service){
        return graphService.getReachable(service);
    }

    public List<String> getPredecessors(String service){
        return graphService.predecessors(service);
    }

    public List<List<String>> getCycles(){
        return graphService.cycles();
    }

    public List<List<String>> getCyclesTarjan(){
        return graphService.cyclesTarjan();
    }

    public Map<String, Object> getShortestPath(String from, String to){
        return graphService.shortestPath(from, to);
    }

    public List<String> getCriticalNodes(int k){
        return graphService.criticalNodes(k);
    }

    public List<String> getCriticalNodesFast(int k, int sampleSize){
        return graphService.criticalNodesFast(k, sampleSize);
    }

    public Map<String, Object> getHealth(String service){
        return healthMonitor.getServiceHealth(service);
    }

    public void shutdown(){
        running = false;
        poller.shutdown();
        workerPool.shutdown();
        try {
            if(!workerPool.awaitTermination(5, TimeUnit.SECONDS)){
                workerPool.shutdownNow();
            }
        } catch (InterruptedException e) {
            workerPool.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
