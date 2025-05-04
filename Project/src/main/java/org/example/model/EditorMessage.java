package org.example.model;

import org.example.crdt.Operation;

public class EditorMessage {
    public enum MessageType {
        OPERATION,
        SYNC_REQUEST,
        SYNC_RESPONSE,
        REDO,
        UNDO
    }

    private MessageType type;
    private String clientId;
    private String content;
    private Operation operation;
    private String documentId;

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

    public String getDocumentId() {
        return documentId;
    }

    public void setDocumentId(String documentId) {
        this.documentId = documentId;
    }

    @Override
    public String toString() {
        return "EditorMessage{" +
                "type=" + type +
                ", clientId='" + clientId + '\'' +
                ", content='" + (content != null ? (content.length() > 20 ? content.substring(0, 20) + "..." : content) : null) + '\'' +
                ", operation=" + operation +
                ", documentId='" + documentId + '\'' +
                '}';
    }
}