package health;

import common.Event;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class HealthMonitor {
    private static final int WINDOW_SIZE = 1000;
    private static final long STALE_THRESHOLD_MS = 5 * 60 * 1000; // 5 minutes
    private final ConcurrentHashMap<String, Deque<Long>> serviceLatency;
    private final ConcurrentHashMap<String, Deque<String>> serviceStatuses;
    private final ConcurrentHashMap<String, Long> lastHeartbeat;

    public HealthMonitor(){
        this.serviceLatency = new ConcurrentHashMap<>();
        this.serviceStatuses = new ConcurrentHashMap<>();
        this.lastHeartbeat = new ConcurrentHashMap<>();
    }

    public void recordHeartbeat(Event event){
        lastHeartbeat.put(event.getFromService(), event.getTimestamp());
    }

    public boolean isStale(String service){
        Long last = lastHeartbeat.get(service);
        if(last == null) return true;
        return (System.currentTimeMillis() - last) > STALE_THRESHOLD_MS;
    }

    public long getLastHeartbeat(String service){
        return lastHeartbeat.getOrDefault(service, -1L);
    }

    public void addEvent(Event event){
        String service = event.getToService();

        // record per-service latency (keyed by toService)
        serviceLatency.computeIfAbsent(service, k -> new LinkedList<>());
        Deque<Long> svcLatDeque = serviceLatency.get(service);
        synchronized(svcLatDeque){
            if(svcLatDeque.size() >= WINDOW_SIZE){
                svcLatDeque.pollFirst();
            }
            svcLatDeque.addLast((long) event.getLatency());
        }

        // record per-service status (keyed by toService)
        serviceStatuses.computeIfAbsent(service, k -> new LinkedList<>());
        Deque<String> svcStatusDeque = serviceStatuses.get(service);
        synchronized(svcStatusDeque){
            if(svcStatusDeque.size() >= WINDOW_SIZE){
                svcStatusDeque.pollFirst();
            }
            svcStatusDeque.addLast(event.getStatus());
        }
    }

    public double getRollingAverageLatency(String service){
        Deque<Long> deque = serviceLatency.get(service);
        if(deque == null || deque.isEmpty()) return Double.MAX_VALUE;
        synchronized(deque){
            long sum = 0;
            for(long val : deque){
                sum += val;
            }
            return (double) sum / deque.size();
        }
    }

    public double calculateServiceP95Latency(String service){
        Deque<Long> deque = serviceLatency.get(service);
        if(deque == null || deque.isEmpty()) return 0.0;
        synchronized(deque){
            List<Long> sorted = new ArrayList<>(deque);
            Collections.sort(sorted);
            int index = (int) Math.ceil(0.95 * sorted.size()) - 1;
            return sorted.get(Math.max(0, index));
        }
    }

    public double calculateServiceErrorRate(String service){
        Deque<String> deque = serviceStatuses.get(service);
        if(deque == null || deque.isEmpty()) return 0.0;
        synchronized(deque){
            long errors = 0;
            for(String status : deque){
                if(!"OK".equalsIgnoreCase(status)){
                    errors++;
                }
            }
            return (double) errors / deque.size();
        }
    }

    public Map<String, Object> getServiceHealth(String service){
        Map<String, Object> health = new HashMap<>();
        health.put("service", service);
        health.put("p95_latency", calculateServiceP95Latency(service));
        health.put("error_rate", calculateServiceErrorRate(service));
        health.put("stale", isStale(service));
        health.put("last_heartbeat", getLastHeartbeat(service));
        return health;
    }

}
