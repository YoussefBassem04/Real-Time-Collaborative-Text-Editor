package org.example.model;

import org.example.crdt.Operation;

public class EditorMessage {
    public enum MessageType {
        OPERATION, USER_JOIN, USER_LIST
    }

    private MessageType type;
    private String sender;
    private String documentId;
    private Operation operation;
    private Object content;

    // Getters & Setters
    public MessageType getType() { return type; }
    public void setType(MessageType type) { this.type = type; }
    public String getSender() { return sender; }
    public void setSender(String sender) { this.sender = sender; }
    public String getDocumentId() { return documentId; }
    public void setDocumentId(String documentId) { this.documentId = documentId; }
    public Operation getOperation() { return operation; }
    public void setOperation(Operation operation) { this.operation = operation; }
    public Object getContent() { return content; }
    public void setContent(Object content) { this.content = content; }
}
