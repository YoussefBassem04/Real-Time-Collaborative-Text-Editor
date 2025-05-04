package org.example.client;

import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import org.example.crdt.Operation;

public class DocumentState {
    private String documentId = "default";
    private final String clientId;
    private List<String> characterIds = new ArrayList<>();
    private final Queue<Operation> operationQueue = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean isProcessingRemoteOperation = new AtomicBoolean(false);
    private final StringProperty connectionStatus = new SimpleStringProperty("Disconnected");
    private String previousContent = "";
    private final Set<String> recentDocuments = new HashSet<>();

    public DocumentState() {
        this.clientId = UUID.randomUUID().toString();
    }

    // Getters and setters
    public String getDocumentId() {
        return documentId;
    }

    public void setDocumentId(String documentId) {
        this.documentId = documentId;
        recentDocuments.add(documentId);
    }

    public String getClientId() {
        return clientId;
    }

    public List<String> getCharacterIds() {
        return characterIds;
    }

    public Queue<Operation> getOperationQueue() {
        return operationQueue;
    }

    public boolean isProcessingRemoteOperation() {
        return isProcessingRemoteOperation.get();
    }

    public void setProcessingRemoteOperation(boolean value) {
        isProcessingRemoteOperation.set(value);
    }

    public StringProperty getConnectionStatus() {
        return connectionStatus;
    }

    public String getPreviousContent() {
        return previousContent;
    }

    public void setPreviousContent(String content) {
        this.previousContent = content;
    }

    public Set<String> getRecentDocuments() {
        return recentDocuments;
    }
}