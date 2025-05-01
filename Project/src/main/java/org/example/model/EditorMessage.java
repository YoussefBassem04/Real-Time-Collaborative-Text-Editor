package org.example.model;

import org.example.crdt.Operation;

public class EditorMessage {
    public enum MessageType {
        OPERATION, CURSOR_UPDATE, USER_JOIN, USER_LEAVE, SYNC_REQUEST, USER_LIST, SYNC_RESPONSE
    }

    private MessageType type;
    private String sender;
    private Operation operation;
    private Object content;
    private String documentId;

    // Getters and setters
    public MessageType getType() { return type; }
    public void setType(MessageType type) { this.type = type; }
    public String getSender() { return sender; }
    public void setSender(String sender) { this.sender = sender; }
    public Operation getOperation() { return operation; }
    public void setOperation(Operation operation) { this.operation = operation; }
    public Object getContent() { return content; }
    public void setContent(Object content) { this.content = content; }
    public String getDocumentId() { return documentId; }
    public void setDocumentId(String documentId) { this.documentId = documentId; }
}