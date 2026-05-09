package health;

import common.Event;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class HealthMonitor {
    private static final int WINDOW_SIZE = 1000;
    private static final long STALE_THRESHOLD_MS = 5 * 60 * 1000; // 5 minutes
    private final ConcurrentHashMap<String, Deque<Long>> latency;
    private final ConcurrentHashMap<String, Deque<String>> statuses;
    private final ConcurrentHashMap<String, Long> lastHeartbeat;

    public HealthMonitor(){
        this.latency = new ConcurrentHashMap<>();
        this.statuses = new ConcurrentHashMap<>();
        this.lastHeartbeat = new ConcurrentHashMap<>();
    }

    private String edgeKey(String from, String to){
        return from + "->" + to;
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
        String key = edgeKey(event.getFromService(), event.getToService());

        // record latency
        latency.computeIfAbsent(key, k -> new LinkedList<>());
        Deque<Long> latencyDeque = latency.get(key);
        synchronized(latencyDeque){
            if(latencyDeque.size() >= WINDOW_SIZE){
                latencyDeque.pollFirst();
            }
            latencyDeque.addLast((long) event.getLatency());
        }

        // record status
        statuses.computeIfAbsent(key, k -> new LinkedList<>());
        Deque<String> statusDeque = statuses.get(key);
        synchronized(statusDeque){
            if(statusDeque.size() >= WINDOW_SIZE){
                statusDeque.pollFirst();
            }
            statusDeque.addLast(event.getStatus());
        }
    }

    public double getRollingAverageLatency(String from, String to){
        String key = edgeKey(from, to);
        Deque<Long> deque = latency.get(key);
        if(deque == null || deque.isEmpty()) return Double.MAX_VALUE;
        synchronized(deque){
            long sum = 0;
            for(long val : deque){
                sum += val;
            }
            return (double) sum / deque.size();
        }
    }

    public double calculateP95Latency(String from, String to){
        String key = edgeKey(from, to);
        Deque<Long> deque = latency.get(key);
        if(deque == null || deque.isEmpty()) return 0.0;
        synchronized(deque){
            List<Long> sorted = new ArrayList<>(deque);
            Collections.sort(sorted);
            int index = (int) Math.ceil(0.95 * sorted.size()) - 1;
            return sorted.get(Math.max(0, index));
        }
    }

    public double calculateErrorRate(String from, String to){
        String key = edgeKey(from, to);
        Deque<String> deque = statuses.get(key);
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
        health.put("stale", isStale(service));
        health.put("last_heartbeat", getLastHeartbeat(service));
        return health;
    }
}
