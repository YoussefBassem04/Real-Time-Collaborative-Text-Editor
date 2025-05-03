package org.example.crdt;

import java.util.List;

public class Operation {
    public enum Type {
        INSERT,
        DELETE
    }

    private Type type;
    private String content;
    private List<String> path;
    private long timestamp;
    private String clientId;

    public Operation() {
    }

    public Operation(Type type, String content, List<String> path, long timestamp, String clientId) {
        this.type = type;
        this.content = content;
        this.path = path;
        this.timestamp = timestamp;
        this.clientId = clientId;
    }

    public Type getType() {
        return type;
    }

    public void setType(Type type) {
        this.type = type;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public List<String> getPath() {
        return path;
    }

    public void setPath(List<String> path) {
        this.path = path;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }

    public String getClientId() {
        return clientId;
    }

    public void setClientId(String clientId) {
        this.clientId = clientId;
    }

    @Override
    public String toString() {
        return "Operation{" +
                "type=" + type +
                ", content='" + content + '\'' +
                ", path=" + path +
                ", timestamp=" + timestamp +
                ", clientId='" + clientId + '\'' +
                '}';
    }
}