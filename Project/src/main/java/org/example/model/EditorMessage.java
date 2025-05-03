package org.example.model;

import org.example.crdt.Operation;

import java.util.List;

public class EditorMessage {
    private MessageType type;
    private String clientId;
    private String documentId;
    private Operation operation;
    private String content;
    private List<String> characterIds; // Add this field

    public enum MessageType {
        OPERATION, SYNC_REQUEST, SYNC_RESPONSE
    }

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

    public Operation getOperation() {
        return operation;
    }

    public void setOperation(Operation operation) {
        this.operation = operation;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
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
                ", operation=" + operation +
                ", content='" + content + '\'' +
                ", characterIds=" + characterIds +
                '}';
    }
}