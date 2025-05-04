package org.example.crdt;

import java.util.List;
import java.util.ArrayList;

public class Operation {

    public enum Type {
        INSERT,
        DELETE,
        SYNC  // New operation type for undo/redo sync
    }

    private Type type;
    private String content;
    private List<String> path;
    private long timestamp;
    private String clientId;
    private List<String> characterIds; // Add this field for sync operations

    public String getReferenceCharId() {
        return referenceCharId;
    }
    public Operation(Type type, String content, List<String> newPath, long l, String clientId, String referenceCharId, int position) {
        this.type = type;
        this.content = content;
        this.path = newPath;
        this.timestamp = l;
        this.clientId = clientId;
        this.characterIds = new ArrayList<>();
        this.referenceCharId = referenceCharId; // Set the reference character ID
        this.position = position; // Set the position
    }
    public int getPosition() {
        return position;
    }

    private String referenceCharId; // New field: ID of the character before this operation's position
    private int position;

    public Operation() {
        // Default constructor for Jackson deserialization
    }

    public Operation(Type type, String content, List<String> path, long timestamp, String clientId) {
        this.type = type;
        this.content = content;
        this.path = path;
        this.timestamp = timestamp;
        this.clientId = clientId;
        this.characterIds = new ArrayList<>();
    }

    // Getters and setters
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

    public List<String> getCharacterIds() {
        return characterIds;
    }

    public void setCharacterIds(List<String> characterIds) {
        this.characterIds = characterIds;
    }
}