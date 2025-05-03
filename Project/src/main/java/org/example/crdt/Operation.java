package org.example.crdt;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class Operation {
    public enum Type { INSERT, DELETE }

    private String id; // Unique operation ID
    private Type type;
    private String content;
    private List<String> path;
    private long timestamp;
    private String clientId;
    private Map<String, Integer> vectorClock; // For causal ordering

    public Operation(Type type, String content, List<String> path, String clientId) {
        this.id = UUID.randomUUID().toString();
        this.type = type;
        this.content = content;
        this.path = path;
        this.timestamp = System.currentTimeMillis();
        this.clientId = clientId;
        this.vectorClock = new HashMap<>();
    }

    // Getters and setters
    public String getId() { return id; }
    public Type getType() { return type; }
    public String getContent() { return content; }
    public List<String> getPath() { return path; }
    public long getTimestamp() { return timestamp; }
    public String getClientId() { return clientId; }
    public Map<String, Integer> getVectorClock() { return new HashMap<>(vectorClock); }
    public void setVectorClock(Map<String, Integer> vectorClock) { this.vectorClock = new HashMap<>(vectorClock); }

    @Override
    public String toString() {
        return "Operation{" +
                "id='" + id + '\'' +
                ", type=" + type +
                ", content='" + content + '\'' +
                ", path=" + path +
                ", timestamp=" + timestamp +
                ", clientId='" + clientId + '\'' +
                ", vectorClock=" + vectorClock +
                '}';
    }
}