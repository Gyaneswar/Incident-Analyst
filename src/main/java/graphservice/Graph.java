package graphservice;

import health.HealthMonitor;
import java.util.*;

public class Graph{
    private static final HashSet<Node> EMPTY = new HashSet<>();
    private final HashMap<String, HashSet<Node>> nodes;
    private final HashMap<String, HashSet<Node>> reverseNodes;
    private final HealthMonitor healthMonitor;


    public Graph(HealthMonitor healthMonitor){
        this.nodes = new HashMap<>();
        this.reverseNodes = new HashMap<>();
        this.healthMonitor = healthMonitor;
    }

    private Graph(HashMap<String, HashSet<Node>> nodes, HashMap<String, HashSet<Node>> reverseNodes, HealthMonitor healthMonitor){
        this.nodes = nodes;
        this.reverseNodes = reverseNodes;
        this.healthMonitor = healthMonitor;
    }

    public Graph snapshot(){
        HashMap<String, HashSet<Node>> nodesCopy = new HashMap<>();
        for(Map.Entry<String, HashSet<Node>> entry : nodes.entrySet()){
            nodesCopy.put(entry.getKey(), new HashSet<>(entry.getValue()));
        }
        HashMap<String, HashSet<Node>> reverseCopy = new HashMap<>();
        for(Map.Entry<String, HashSet<Node>> entry : reverseNodes.entrySet()){
            reverseCopy.put(entry.getKey(), new HashSet<>(entry.getValue()));
        }
        return new Graph(nodesCopy, reverseCopy, healthMonitor);
    }

    public Set<String> keySet(){
        return nodes.keySet();
    }

    public Set<String> keySetReversed(){
        return reverseNodes.keySet();
    }

    public HashSet<Node> getDependents(String serviceName){
        return nodes.getOrDefault(serviceName, EMPTY);
    }

    public HashSet<Node> getPredecessors(String serviceName){
        return reverseNodes.getOrDefault(serviceName, EMPTY);
    }

     void put(Node from, Node to){
        String fromServiceName = from.nodeName;
        String toServiceName = to.nodeName;

        // forward edge: from -> to
        if(nodes.containsKey(fromServiceName)){
            HashSet<Node> set = nodes.get(fromServiceName);
            for(Node node : set){
                if(node.nodeName.equals(toServiceName)){
                    node.latency = (int) healthMonitor.getRollingAverageLatency(toServiceName);
                    return;
                }
            }
            set.add(to);
            nodes.put(fromServiceName, set);
        }else{
            HashSet<Node> set = new HashSet<>();
            set.add(to);
            nodes.put(fromServiceName, set);
        }

        // reverse edge: to -> from (for dependents query)
        if(reverseNodes.containsKey(toServiceName)){
            HashSet<Node> set = reverseNodes.get(toServiceName);
            for(Node node : set){
                if(node.nodeName.equals(fromServiceName)){
                    return;
                }
            }
            set.add(from);
        }else{
            HashSet<Node> set = new HashSet<>();
            set.add(from);
            reverseNodes.put(toServiceName, set);
        }
    }


    void removeEdge(String from, String to){
        HashSet<Node> forward = nodes.get(from);
        if(forward != null){
            forward.removeIf(node -> node.nodeName.equals(to));
        }

        HashSet<Node> reverse = reverseNodes.get(to);
        if(reverse != null){
            reverse.removeIf(node -> node.nodeName.equals(from));
        }
    }

    @Override
    public String toString(){
        StringBuilder sb = new StringBuilder("Graph[\n");
        for(Map.Entry<String, HashSet<Node>> entry : nodes.entrySet()){
            sb.append("  ").append(entry.getKey()).append(" -> ").append(entry.getValue()).append("\n");
        }
        sb.append("]");
        return sb.toString();
    }
}