package faulttolerance;

import common.Event;
import common.EventType;

import java.io.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

//This class will write/read events to/from a CSV file
public class EventToFile {
    private static final String FILE_PATH = "events.csv";
    private final ExecutorService writeExecutor = Executors.newSingleThreadExecutor();
    private BufferedReader reader;

    public void writeEvents(Event event){
        writeExecutor.submit(() -> {
            try (BufferedWriter writer = new BufferedWriter(new FileWriter(FILE_PATH, true))){
                writer.write(String.join(",",
                        event.getEventId(),
                        event.getEventType().name(),
                        event.getFromService(),
                        event.getToService(),
                        String.valueOf(event.getLatency()),
                        String.valueOf(event.getTimestamp()),
                        event.getStatus()));
                writer.newLine();
            } catch (IOException e) {
                System.err.println("Failed to write event: " + e.getMessage());
            }
        });
    }

    public void shutdown(){
        writeExecutor.shutdown();
        try {
            if(!writeExecutor.awaitTermination(5, TimeUnit.SECONDS)){
                writeExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            writeExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    public Event readEvents(){
        try {
            if (reader == null) {
                File file = new File(FILE_PATH);
                if (!file.exists()) return null;
                reader = new BufferedReader(new FileReader(file));
            }
            String line = reader.readLine();
            if (line == null) {
                reader.close();
                reader = null;
                return null;
            }
            return parseLine(line);
        } catch (IOException e) {
            System.err.println("Failed to read event: " + e.getMessage());
            return null;
        }
    }

    private Event parseLine(String line){
        String[] parts = line.split(",");
        Event event = new Event();
        event.setEventId(parts[0]);
        event.setEventType(EventType.valueOf(parts[1]));
        event.setFromService(parts[2]);
        event.setToService(parts[3]);
        event.setLatency(Integer.parseInt(parts[4]));
        event.setTimestamp(Long.parseLong(parts[5]));
        event.setStatus(parts[6]);
        return event;
    }
}
