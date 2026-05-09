package graphservice;

import java.util.*;

public class Node{
    int latency;
    String nodeName;
    int counter;

    Node(int latency, String nodeName){
        this.latency = latency;
        this.nodeName = nodeName;
        this.counter = 1;
    }

    @Override
    public boolean equals(Object o){
        if(this == o) return true;
        if(o == null || getClass() != o.getClass()) return false;
        Node node = (Node) o;
        return nodeName.equals(node.nodeName);
    }

    @Override
    public int hashCode(){
        return nodeName.hashCode();
    }
}
