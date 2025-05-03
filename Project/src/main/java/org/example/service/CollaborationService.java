package org.example.service;

import org.example.crdt.Operation;
import org.example.crdt.TreeBasedCRDT;
import org.example.model.EditorMessage;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

@Service
public class CollaborationService {
    private final Map<String, TreeBasedCRDT> documents = new ConcurrentHashMap<>();
    private final Map<String, Long> clientLastSeen = new ConcurrentHashMap<>();
    private final Map<String, String> clientToDocument = new ConcurrentHashMap<>();
    private final Map<String, Integer> clientCursors = new ConcurrentHashMap<>();
    private final Set<String> connectedClients = ConcurrentHashMap.newKeySet();
    private final Map<String, Queue<Operation>> operationBuffers = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Integer>> vectorClocks = new ConcurrentHashMap<>();

    public String createDocument() {
        String documentId = UUID.randomUUID().toString();
        documents.put(documentId, new TreeBasedCRDT());
        operationBuffers.put(documentId, new ConcurrentLinkedQueue<>());
        vectorClocks.put(documentId, new ConcurrentHashMap<>());
        return documentId;
    }

    public boolean documentExists(String documentId) {
        return documents.containsKey(documentId);
    }

    public void applyOperation(String documentId, Operation operation) {
        if (operation == null || operation.getPath().isEmpty() || !documents.containsKey(documentId)) {
            return;
        }
        operationBuffers.get(documentId).offer(operation);
        processBufferedOperations(documentId);
    }

    private void processBufferedOperations(String documentId) {
        Queue<Operation> buffer = operationBuffers.get(documentId);
        Map<String, Integer> docVectorClock = vectorClocks.get(documentId);
        List<Operation> readyOperations = new ArrayList<>();

        // Collect operations that are causally ready
        for (Operation op : buffer) {
            boolean canApply = true;
            for (Map.Entry<String, Integer> entry : op.getVectorClock().entrySet()) {
                String client = entry.getKey();
                int version = entry.getValue();
                if (docVectorClock.getOrDefault(client, 0) < version - 1) {
                    canApply = false;
                    break;
                }
            }
            if (canApply) {
                readyOperations.add(op);
            }
        }

        // Apply ready operations and update vector clock
        synchronized (documents.get(documentId)) {
            for (Operation op : readyOperations) {
                documents.get(documentId).applyOperation(op);
                buffer.remove(op);
                clientLastSeen.put(op.getClientId(), System.currentTimeMillis());
                docVectorClock.put(op.getClientId(), docVectorClock.getOrDefault(op.getClientId(), 0) + 1);
            }
        }
    }

    public String getDocumentContent(String documentId) {
        if (!documents.containsKey(documentId)) {
            return "";
        }
        synchronized (documents.get(documentId)) {
            return documents.get(documentId).getText();
        }
    }

    public Operation generateInsertOperation(String documentId, String content, List<String> path, String clientId) {
        if (!documents.containsKey(documentId)) {
            return null;
        }
        synchronized (documents.get(documentId)) {
            return documents.get(documentId).generateInsertOp(content, path, clientId);
        }
    }

    public Operation generateDeleteOperation(String documentId, String content, List<String> path, String clientId) {
        if (!documents.containsKey(documentId)) {
            return null;
        }
        synchronized (documents.get(documentId)) {
            return documents.get(documentId).generateDeleteOp(content, path, clientId);
        }
    }

    public void clientConnected(String clientId, String documentId) {
        if (documents.containsKey(documentId)) {
            connectedClients.add(clientId);
            clientLastSeen.put(clientId, System.currentTimeMillis());
            clientToDocument.put(clientId, documentId);
        }
    }

    public void clientDisconnected(String clientId) {
        connectedClients.remove(clientId);
        clientToDocument.remove(clientId);
        clientCursors.remove(clientId);
    }

    public void updateCursor(String clientId, int cursorPosition) {
        clientCursors.put(clientId, cursorPosition);
    }

    public Map<String, Integer> getCursors() {
        return new HashMap<>(clientCursors);
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
        if (message == null || message.getClientId() == null) {
            return null;
        }
        String documentId = clientToDocument.get(message.getClientId());
        if (documentId == null) {
            return null;
        }

        switch (message.getType()) {
            case OPERATION:
                EditorMessage opResponse = new EditorMessage();
                opResponse.setType(EditorMessage.MessageType.OPERATION);
                opResponse.setOperations(message.getOperations());
                opResponse.setClientId(message.getClientId());
                opResponse.setDocumentId(documentId);
                message.getOperations().forEach(op -> applyOperation(documentId, op));
                return opResponse;

            case SYNC_REQUEST:
                EditorMessage syncResponse = new EditorMessage();
                syncResponse.setType(EditorMessage.MessageType.SYNC_RESPONSE);
                if (message.getContent() != null && !message.getContent().isEmpty()) {
                    synchronized (documents.get(documentId)) {
                        TreeBasedCRDT crdt = new TreeBasedCRDT();
                        documents.put(documentId, crdt);
                        for (int i = 0; i < message.getContent().length(); i++) {
                            List<String> path = List.of("root", i + ":" + message.getClientId());
                            crdt.applyOperation(new Operation(Operation.Type.INSERT, String.valueOf(message.getContent().charAt(i)), path, message.getClientId()));
                        }
                    }
                }
                syncResponse.setContent(getDocumentContent(documentId));
                syncResponse.setClientId(message.getClientId());
                syncResponse.setDocumentId(documentId);
                return syncResponse;

            case CURSOR_UPDATE:
                updateCursor(message.getClientId(), message.getCursorPosition());
                EditorMessage cursorResponse = new EditorMessage();
                cursorResponse.setType(EditorMessage.MessageType.CURSOR_UPDATE);
                cursorResponse.setClientId(message.getClientId());
                cursorResponse.setDocumentId(documentId);
                cursorResponse.setCursorPosition(message.getCursorPosition());
                return cursorResponse;

            default:
                return null;
        }
    }

    public Map<String, Object> getDocumentState(String documentId) {
        if (!documents.containsKey(documentId)) {
            return new HashMap<>();
        }
        synchronized (documents.get(documentId)) {
            return documents.get(documentId).getDocumentState();
        }
    }
}