package graphservice;

import faulttolerance.EventToFile;
import health.HealthMonitor;

import java.util.*;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class GraphService{
    Graph graph;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private final HealthMonitor healthMonitor;

    public GraphService(HealthMonitor healthMonitor){
        this.healthMonitor = healthMonitor;
        this.graph = new Graph(healthMonitor);
        init();
    }

    private void init(){
        //use the faulttolerance.EventToFile to read the events from the file
        EventToFile eventToFile = new EventToFile();
        System.out.println("Reading events from file");
        common.Event event;
        while((event = eventToFile.readEvents()) != null){
            System.out.println("Processing event: " + event.getEventId());
            if(event.getEventType() == common.EventType.DEPENDENCY_OBSERVED){
                Node fromService = new Node(event.getLatency(), event.getFromService());
                Node toService = new Node(event.getLatency(), event.getToService());
                healthMonitor.addEvent(event);
                addEvent(fromService, toService);
            }
        }
    }

    public void addEvent(Node fromService, Node toService){
        lock.writeLock().lock();
        try {
            System.out.println("Adding event from " + fromService + " to " + toService);
            graph.put(fromService, toService);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void removeNode(String serviceName){
        lock.writeLock().lock();
        try {
            System.out.println("Removing service: " + serviceName);
            graph.remove(serviceName);
        } finally {
            lock.writeLock().unlock();
        }
    }

    private Graph snapshot(){
        lock.writeLock().lock();
        try {
            Graph snap = graph.snapshot();
            System.out.println("Snapshot: " + snap);
            return snap;
        } finally {
            lock.writeLock().unlock();
        }
    }

    //use BFS to find all the reachable nodes
    public List<String> getReachable(String serviceName){
        Graph snap = snapshot();
        Queue<String> queue = new LinkedList<>();
        queue.add(serviceName);
        List<String> reachableNodes = new ArrayList<>();
        HashSet<String> visited = new HashSet<>();

        while(!queue.isEmpty()){
            String node = queue.poll();
            if(visited.contains(node)) continue;
            visited.add(node);
            reachableNodes.add(node);

            for(Node neighbor : snap.getDependents(node)){
                if(visited.contains(neighbor.nodeName)) continue;
                queue.add(neighbor.nodeName);
            }
        }
        return reachableNodes;
    }

    //find all the incoming edges to the particular service
    public List<String> predecessors(String serviceName){
        Graph snap = snapshot();
        Queue<String> queue = new LinkedList<>();
        queue.add(serviceName);
        List<String> reachableNodes = new ArrayList<>();
        HashSet<String> visited = new HashSet<>();

        while(!queue.isEmpty()){
            String node = queue.poll();
            if(visited.contains(node)) continue;
            visited.add(node);
            reachableNodes.add(node);

            for(Node neighbor : snap.getPredecessors(node)){
                if(visited.contains(neighbor.nodeName)) continue;
                queue.add(neighbor.nodeName);
            }
        }
        return reachableNodes;
    }

    //find all cycles in the graph using kosaraju's algorithm
    //pass 1: DFS on original graph, record finish order
    //pass 2: DFS on reversed graph in reverse finish order, each tree is an SCC
    // This is will result in scc, but in a directed graph, the cycles are present within scc
    public List<List<String>> cycles(){
        Graph snap = snapshot();
        HashSet<String> visited = new HashSet<>();
        Stack<String> finishStack = new Stack<>();

        // Pass 1: DFS on original graph, record finish order
        for(String node : snap.keySet()){
            if(!visited.contains(node)){
                dfsForward(snap, node, visited, finishStack);
            }
        }

        // Pass 2: DFS on reversed graph in reverse finish order
        List<List<String>> sccs = new ArrayList<>();
        visited.clear();
        while(!finishStack.isEmpty()){
            String node = finishStack.pop();
            if(!visited.contains(node)){
                List<String> component = new ArrayList<>();
                dfsReverse(snap, node, visited, component);
                if(component.size() > 1){
                    sccs.add(component);
                }
            }
        }
        return sccs;
    }

    private void dfsForward(Graph g, String node, HashSet<String> visited, Stack<String> finishStack){
        visited.add(node);
        for(Node neighbor : g.getDependents(node)){
            if(!visited.contains(neighbor.nodeName)){
                dfsForward(g, neighbor.nodeName, visited, finishStack);
            }
        }
        finishStack.push(node);
    }

    private void dfsReverse(Graph g, String node, HashSet<String> visited, List<String> component){
        visited.add(node);
        component.add(node);
        for(Node neighbor : g.getPredecessors(node)){
            if(!visited.contains(neighbor.nodeName)){
                dfsReverse(g, neighbor.nodeName, visited, component);
            }
        }
    }



    //use Dijkstra to find the shortest path
    public List<String> shortestPath(String fromService, String toService){
        Graph snap = snapshot();
        HashMap<String, Double> dist = new HashMap<>();
        HashMap<String, String> previous = new HashMap<>();
        HashSet<String> settled = new HashSet<>();
        dist.put(fromService, 0.0);
        PriorityQueue<String> pq = new PriorityQueue<>((a, b) -> dist.get(a).compareTo(dist.get(b)));
        pq.add(fromService);
        while(!pq.isEmpty()) {
            String start = pq.poll();
            if(settled.contains(start)) continue;
            settled.add(start);
            if(start.equals(toService)) break;
            for (Node node : snap.getDependents(start)) {
                String currentNode = node.nodeName;
                double currentDist = dist.getOrDefault(currentNode, (double) Integer.MAX_VALUE);
                double newDist = dist.get(start) + node.latency;
                if(newDist < currentDist){
                    dist.put(currentNode, newDist);
                    previous.put(currentNode, start);
                    pq.add(currentNode);
                }
            }
        }

        // reconstruct path by backtracking from target
        if(!previous.containsKey(toService) && !fromService.equals(toService)){
            return Collections.emptyList();
        }
        LinkedList<String> path = new LinkedList<>();
        String current = toService;
        while(current != null){
            path.addFirst(current);
            current = previous.get(current);
        }
        return path;
    }

    //betweenness centrality using Brandes' algorithm
    //for each source, run Dijkstra and accumulate dependency scores
    //nodes that appear on the most shortest paths are the most critical
    public List<String> criticalNodes(int k){
        Graph snap = snapshot();
        HashMap<String, Double> centrality = new HashMap<>();
        Set<String> allNodes = snap.keySet();
        for(String node : allNodes){
            centrality.put(node, 0.0);
        }

        for(String source : allNodes){
            // Dijkstra phase
            Stack<String> stack = new Stack<>();
            HashMap<String, List<String>> predecessors = new HashMap<>();
            HashMap<String, Double> dist = new HashMap<>();
            HashMap<String, Double> sigma = new HashMap<>();

            for(String node : allNodes){
                predecessors.put(node, new ArrayList<>());
                dist.put(node, (double) Integer.MAX_VALUE);
                sigma.put(node, 0.0);
            }
            dist.put(source, 0.0);
            sigma.put(source, 1.0);

            HashSet<String> settled = new HashSet<>();
            PriorityQueue<String> pq = new PriorityQueue<>((a, b) -> dist.get(a).compareTo(dist.get(b)));
            pq.add(source);

            while(!pq.isEmpty()){
                String v = pq.poll();
                if(settled.contains(v)) continue;
                settled.add(v);
                if(dist.get(v) == Integer.MAX_VALUE) break;
                stack.push(v);

                for(Node neighbor : snap.getDependents(v)){
                    String w = neighbor.nodeName;
                    double newDist = dist.get(v) + neighbor.latency;
                    // found a shorter path
                    if(newDist < dist.get(w)){
                        dist.put(w, newDist);
                        sigma.put(w, 0.0);
                        predecessors.get(w).clear();
                        pq.add(w);
                    }
                    // found an equally short path
                    if(newDist == dist.get(w)){
                        sigma.put(w, sigma.get(w) + sigma.get(v));
                        predecessors.get(w).add(v);
                    }
                }
            }

            // back-propagation phase
            HashMap<String, Double> delta = new HashMap<>();
            for(String node : allNodes){
                delta.put(node, 0.0);
            }

            while(!stack.isEmpty()){
                String w = stack.pop();
                for(String v : predecessors.get(w)){
                    double contribution = (sigma.get(v) / sigma.get(w)) * (1.0 + delta.get(w));
                    delta.put(v, delta.get(v) + contribution);
                }
                if(!w.equals(source)){
                    centrality.put(w, centrality.get(w) + delta.get(w));
                }
            }
        }

        // return top-k nodes sorted by centrality descending
        List<String> sorted = new ArrayList<>(centrality.keySet());
        sorted.sort((a, b) -> Double.compare(centrality.get(b), centrality.get(a)));
        return sorted.subList(0, Math.min(k, sorted.size()));
    }
}
