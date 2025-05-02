package org.example.model;

import org.example.crdt.Operation;

public class EditorMessage {
    private MessageType type;
    private Operation operation;
    private String content;
    private String clientId;

    public enum MessageType {
        OPERATION,
        SYNC_REQUEST,
        SYNC_RESPONSE
    }

    public EditorMessage() {}

    public EditorMessage(MessageType type, Operation operation, String content, String clientId) {
        this.type = type;
        this.operation = operation;
        this.content = content;
        this.clientId = clientId;
    }

    public MessageType getType() { return type; }
    public void setType(MessageType type) { this.type = type; }
    public Operation getOperation() { return operation; }
    public void setOperation(Operation operation) { this.operation = operation; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public String getClientId() { return clientId; }
    public void setClientId(String clientId) { this.clientId = clientId; }
}