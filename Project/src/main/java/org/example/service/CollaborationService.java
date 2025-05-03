package org.example.service;

import org.example.crdt.Operation;
import org.example.model.EditorMessage;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class CollaborationService {

    // Map of documentId -> document content
    private final Map<String, StringBuilder> documentContents = new ConcurrentHashMap<>();

    // Map of clientId -> documentId (to track which document each client is editing)
    private final Map<String, String> clientDocumentMap = new ConcurrentHashMap<>();

    // Keeping track of all operations for possible conflict resolution
    private final Map<String, java.util.List<Operation>> documentOperations = new ConcurrentHashMap<>();

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

        // Initialize document content if it doesn't exist
        documentContents.putIfAbsent(documentId, new StringBuilder());
        StringBuilder currentContent = documentContents.get(documentId);

        switch (message.getType()) {
            case SYNC_REQUEST:
                EditorMessage syncResponse = new EditorMessage();
                syncResponse.setType(EditorMessage.MessageType.SYNC_RESPONSE);
                syncResponse.setClientId("SERVER");
                syncResponse.setDocumentId(documentId);
                syncResponse.setContent(currentContent.toString());
                System.out.println("Sending SYNC_RESPONSE with content: " + currentContent);
                return syncResponse;

            case OPERATION:
                Operation operation = message.getOperation();
                if (operation != null) {
                    // Track operation for this document
                    documentOperations.putIfAbsent(documentId, new java.util.ArrayList<>());
                    documentOperations.get(documentId).add(operation);

                    // Apply operation to document state
                    applyOperation(currentContent, operation);

                    // Return the operation message to broadcast to all clients
                    return message;
                }
                break;
        }

        return null;
    }

    private void applyOperation(StringBuilder document, Operation operation) {
        int position = calculatePositionFromPath(document.toString(), operation.getPath());

        switch (operation.getType()) {
            case INSERT:
                if (position >= 0 && position <= document.length()) {
                    document.insert(position, operation.getContent());
                    System.out.println("Applied INSERT at position " + position + ": '" +
                            operation.getContent() + "', Document now: " + document);
                }
                break;

            case DELETE:
                if (position >= 0 && position < document.length()) {
                    String contentToDelete = operation.getContent();
                    int endPos = Math.min(position + contentToDelete.length(), document.length());

                    // Verify the content to delete matches what's actually at that position
                    String actualContent = document.substring(position, endPos);
                    if (actualContent.equals(contentToDelete)) {
                        document.delete(position, endPos);
                        System.out.println("Applied DELETE at position " + position +
                                ", deleted: '" + contentToDelete + "', Document now: " + document);
                    } else {
                        System.out.println("Warning: Content mismatch for DELETE. Expected: '" +
                                contentToDelete + "', Actual: '" + actualContent + "'");
                        // Still delete the content at that position for simplicity
                        document.delete(position, endPos);
                        System.out.println("Applied DELETE anyway at position " + position +
                                ", Document now: " + document);
                    }
                } else {
                    System.out.println("Invalid position for DELETE: " + position +
                            " (document length: " + document.length() + ")");
                }
                break;
        }

    }

    private int calculatePositionFromPath(String document, java.util.List<String> path) {
        if (path == null || path.isEmpty()) return 0;

        if (path.contains("start")) return 0;
        if (path.contains("end")) return document.length();

        for (int i = 0; i < document.length(); i++) {
            boolean matches = true;
            for (String segment : path) {
                if (segment.startsWith("after-")) {
                    int charCode = Integer.parseInt(segment.substring(6));
                    if (i == 0 || (int) document.charAt(i - 1) != charCode) {
                        matches = false;
                        break;
                    }
                } else if (segment.startsWith("before-")) {
                    int charCode = Integer.parseInt(segment.substring(7));
                    if (i >= document.length() || (int) document.charAt(i) != charCode) {
                        matches = false;
                        break;
                    }
                }
            }
            if (matches) return i;
        }

        return document.length(); // Default to end of document if path can't be resolved
    }

    public void handleClientDisconnect(String clientId) {
        // Remove from client-document mapping
        clientDocumentMap.remove(clientId);
        System.out.println("Client disconnected: " + clientId);
    }
}