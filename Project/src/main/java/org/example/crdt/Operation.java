package org.example.crdt;

import java.util.List;

public class Operation {
    public enum Type {
        INSERT,
        DELETE
    }

    public Type type;
    public String content;
    private List<String> path;
    private long timestamp;
    private String clientId;
    public int position;
    public int length;

    public Operation() {
    }
    public Operation(int position, int length) {
        this.type = Type.DELETE;
        this.position = position;
        this.length = length;
    }

    /**
     * Constructor for insert operations
     * @param text Text to insert
     * @param position Position to insert at
     */
    public Operation(String text, int position) {
        this.type = Type.INSERT;
        this.content = text;
        this.position = position;
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