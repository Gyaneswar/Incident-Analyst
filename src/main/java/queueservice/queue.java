package queueservice;

import common.Event;

import java.util.concurrent.LinkedBlockingQueue;



public class queue {
    private final int CAPACITY = 10000;
    LinkedBlockingQueue<Event> queue;

    public queue(){
        this.queue = new LinkedBlockingQueue<>(this.CAPACITY);
    }

    public void publish(Event event) throws InterruptedException {
        queue.put(event);
    }

    public Event consume(){
        return queue.poll();
    }
}
