package org.example.model;

import org.example.crdt.Operation;

import java.util.List;

public class EditorMessage {
    public enum MessageType {
        SYNC_REQUEST,
        SYNC_RESPONSE,
        OPERATION
    }

    private MessageType type;
    private String clientId;
    private String documentId;
    private String content;
    private Operation operation;
    private List<String> nodeIds;

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

    public List<String> getNodeIds() {
        return nodeIds;
    }

    public void setNodeIds(List<String> nodeIds) {
        this.nodeIds = nodeIds;
    }

    @Override
    public String toString() {
        return "EditorMessage{" +
                "type=" + type +
                ", clientId='" + clientId + '\'' +
                ", documentId='" + documentId + '\'' +
                ", content='" + content + '\'' +
                ", operation=" + operation +
                ", nodeIds=" + nodeIds +
                '}';
    }
}