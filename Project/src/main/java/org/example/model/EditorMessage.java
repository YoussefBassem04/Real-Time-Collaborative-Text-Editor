package org.example.model;

import org.example.crdt.Operation;

import java.util.List;

public class EditorMessage {
    public enum MessageType {
        OPERATION, SYNC_REQUEST, SYNC_RESPONSE, CREATE_DOCUMENT, CREATE_DOCUMENT_RESPONSE,
        CURSOR_UPDATE
    }

    private MessageType type;
    private List<Operation> operations; // Support batch operations
    private String content;
    private String clientId;
    private String documentId;
    private Integer cursorPosition;

    public EditorMessage() {}

    public MessageType getType() { return type; }
    public void setType(MessageType type) { this.type = type; }
    public List<Operation> getOperations() { return operations; }
    public void setOperations(List<Operation> operations) { this.operations = operations; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public String getClientId() { return clientId; }
    public void setClientId(String clientId) { this.clientId = clientId; }
    public String getDocumentId() { return documentId; }
    public void setDocumentId(String documentId) { this.documentId = documentId; }
    public Integer getCursorPosition() { return cursorPosition; }
    public void setCursorPosition(Integer cursorPosition) { this.cursorPosition = cursorPosition; }
}