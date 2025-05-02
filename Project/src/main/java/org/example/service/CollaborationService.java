package org.example.service;

import org.example.crdt.Operation;
import org.example.crdt.TreeBasedCRDT;
import org.example.model.EditorMessage;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class CollaborationService {
    private final TreeBasedCRDT crdt = new TreeBasedCRDT();
    private final Map<String, Long> clientLastSeen = new ConcurrentHashMap<>();
    private final Object documentLock = new Object();
    private final Set<String> connectedClients = ConcurrentHashMap.newKeySet();

    public void applyOperation(Operation operation) {
        synchronized (documentLock) {
            clientLastSeen.put(operation.getClientId(), System.currentTimeMillis());
            crdt.applyOperation(operation);
        }
    }

    public String getDocumentContent() {
        synchronized (documentLock) {
            return crdt.getText();
        }
    }

    public Operation generateInsertOperation(String content, List<String> path, String clientId) {
        synchronized (documentLock) {
            return crdt.generateInsertOp(content, path, clientId);
        }
    }

    public Operation generateDeleteOperation(String content, List<String> path, String clientId) {
        synchronized (documentLock) {
            return crdt.generateDeleteOp(content, path, clientId);
        }
    }

    public void clientConnected(String clientId) {
        connectedClients.add(clientId);
        clientLastSeen.put(clientId, System.currentTimeMillis());
    }

    public void clientDisconnected(String clientId) {
        connectedClients.remove(clientId);
    }

    public Set<String> getConnectedClients() {
        return Collections.unmodifiableSet(connectedClients);
    }

    public boolean isClientActive(String clientId) {
        Long lastSeen = clientLastSeen.get(clientId);
        if (lastSeen == null) return false;
        return (System.currentTimeMillis() - lastSeen) < 30000;
    }

    public EditorMessage processMessage(EditorMessage message) {
        switch (message.getType()) {
            case OPERATION:
                // Apply the operation to the CRDT
                applyOperation(message.getOperation());

                // Return an updated message to broadcast
                EditorMessage response = new EditorMessage();
                response.setType(EditorMessage.MessageType.OPERATION);
                response.setOperation(message.getOperation());  // Set the operation data to send out
                return response;  // ✅ Send this updated message out to others

            case SYNC_REQUEST:
                // Handle sync request and send back document content
                EditorMessage syncResponse = new EditorMessage();
                syncResponse.setType(EditorMessage.MessageType.SYNC_RESPONSE);
                syncResponse.setContent(getDocumentContent());
                return syncResponse;

            case SYNC_RESPONSE:
                // No action needed here for now
                return null;

            default:
                return null;
        }
    }


    public Map<String, Object> getDocumentState() {
        synchronized (documentLock) {
            return crdt.getDocumentState();
        }
    }
}