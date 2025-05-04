package org.example.model;

import org.example.crdt.Operation;

import java.util.List;

public class EditorMessage {
    public enum MessageType {
        SYNC_REQUEST,
        SYNC_RESPONSE,
        OPERATION, FULL_STATE
    }

    private MessageType type;
    private String clientId;
    private String documentId;
    private String content;
    private Operation operation;
    private List<String> characterIds;  // Added field for character IDs

    // Getters and setters
    public MessageType getType() {
        return type;
    }

    public void setType(MessageType type) {
        this.type = type;
    }

    public String getClientId() {
        return clientId;
    }

    public void setClientId(String clientId) {
        this.clientId = clientId;
    }

    public String getDocumentId() {
        return documentId;
    }

    public void setDocumentId(String documentId) {
        this.documentId = documentId;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public Operation getOperation() {
        return operation;
    }

    public void setOperation(Operation operation) {
        this.operation = operation;
    }

    public List<String> getCharacterIds() {
        return characterIds;
    }

    public void setCharacterIds(List<String> characterIds) {
        this.characterIds = characterIds;
    }

    @Override
    public String toString() {
        return "EditorMessage{" +
                "type=" + type +
                ", clientId='" + clientId + '\'' +
                ", documentId='" + documentId + '\'' +
                ", content='" + (content != null ? (content.length() > 20 ? content.substring(0, 20) + "..." : content) : null) + '\'' +
                ", operation=" + operation +
                ", characterIds=" + (characterIds != null ? characterIds.size() : 0) + " ids" +
                '}';
    }

    public void setTimestamp(long timeMillis) {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'setTimestamp'");
    }
}
