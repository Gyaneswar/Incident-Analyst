package graphservice;

import health.HealthMonitor;
import java.util.*;

public class Graph{
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
        return nodes.getOrDefault(serviceName, new HashSet<>());
    }

    public HashSet<Node> getPredecessors(String serviceName){
        return reverseNodes.getOrDefault(serviceName, new HashSet<>());
    }

     void put(Node from, Node to){
        String fromServiceName = from.nodeName;
        String toServiceName = to.nodeName;

        // forward edge: from -> to
        if(nodes.containsKey(fromServiceName)){
            HashSet<Node> set = nodes.get(fromServiceName);
            for(Node node : set){
                if(node.nodeName.equals(toServiceName)){
                    node.latency = (int) healthMonitor.getRollingAverageLatency(fromServiceName, toServiceName);
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


    void remove(String serviceName){
        for(String key : new ArrayList<>(nodes.keySet())){
            nodes.get(key).removeIf(node -> node.nodeName.equals(serviceName));
        }
        nodes.remove(serviceName);

        for(String key : new ArrayList<>(reverseNodes.keySet())){
            reverseNodes.get(key).removeIf(node -> node.nodeName.equals(serviceName));
        }
        reverseNodes.remove(serviceName);
    }

}