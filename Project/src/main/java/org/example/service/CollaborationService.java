package org.example.service;

import org.example.crdt.Operation;
import org.example.crdt.TreeBasedCRDT;
import org.example.model.EditorMessage;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class CollaborationService {

    // Map of documentId -> TreeBasedCRDT instance
    private final Map<String, TreeBasedCRDT> documentCRDTs = new ConcurrentHashMap<>();

    // Map of clientId -> documentId (to track which document each client is editing)
    private final Map<String, String> clientDocumentMap = new ConcurrentHashMap<>();

    public EditorMessage processMessage(EditorMessage message) {
        String documentId = message.getDocumentId();
        if (documentId == null || documentId.isEmpty()) {
            documentId = "default";
            message.setDocumentId(documentId);
        }

        String clientId = message.getClientId();
        if (clientId != null) {
            // Track which document this client is editing
            clientDocumentMap.put(clientId, documentId);
        }

        // Initialize CRDT if it doesn't exist
        documentCRDTs.putIfAbsent(documentId, new TreeBasedCRDT("SERVER"));
        TreeBasedCRDT crdt = documentCRDTs.get(documentId);

        switch (message.getType()) {
            case SYNC_REQUEST:
                EditorMessage syncResponse = new EditorMessage();
                syncResponse.setType(EditorMessage.MessageType.SYNC_RESPONSE);
                syncResponse.setClientId("SERVER");
                syncResponse.setDocumentId(documentId);
                syncResponse.setContent(crdt.getContent());
                System.out.println("Sending SYNC_RESPONSE with content: " + crdt.getContent());
                return syncResponse;

            case OPERATION:
                Operation operation = message.getOperation();
                if (operation != null) {
                    // Apply operation to CRDT
                    boolean success = crdt.applyOperation(operation);
                    if (success) {
                        System.out.println("Applied operation: " + operation);
                        System.out.println("Document state: " + crdt.getContent());
                        crdt.debugPrintStructure();
                        
                        // Return the operation message to broadcast to all clients
                        return message;
                    } else {
                        System.out.println("Failed to apply operation: " + operation);
                    }
                }
                break;
        }

        return null;
    }

    public void handleClientDisconnect(String clientId) {
        // Remove from client-document mapping
        clientDocumentMap.remove(clientId);
        System.out.println("Client disconnected: " + clientId);
    }
}