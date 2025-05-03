package org.example.service;

import org.example.crdt.Operation;
import org.example.model.EditorMessage;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.ArrayList;
import java.util.List;

@Service
public class CollaborationService {

    // Map of documentId -> document content
    private final Map<String, StringBuilder> documentContents = new ConcurrentHashMap<>();

    // Map of clientId -> documentId (to track which document each client is editing)
    private final Map<String, String> clientDocumentMap = new ConcurrentHashMap<>();

    // Keeping track of all operations for possible conflict resolution
    private final Map<String, java.util.List<Operation>> documentOperations = new ConcurrentHashMap<>();
    
    // Map of documentId -> list of character IDs for CRDT
    private final Map<String, List<String>> documentCharacterIds = new ConcurrentHashMap<>();

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
        
        // Initialize character IDs list if it doesn't exist
        documentCharacterIds.putIfAbsent(documentId, new ArrayList<>());
        List<String> charIds = documentCharacterIds.get(documentId);

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
                    applyOperation(documentId, operation);

                    // Return the operation message to broadcast to all clients
                    return message;
                }
                break;
        }

        return null;
    }

    private void applyOperation(String documentId, Operation operation) {
        StringBuilder document = documentContents.get(documentId);
        List<String> charIds = documentCharacterIds.get(documentId);
        
        // Calculate position from path, taking into account our character ID list
        int position = calculatePositionFromPath(document.toString(), charIds, operation.getPath());

        switch (operation.getType()) {
            case INSERT:
                if (position >= 0 && position <= document.length()) {
                    document.insert(position, operation.getContent());
                    
                    // Generate and insert character IDs
                    List<String> newCharIds = new ArrayList<>();
                    for (int i = 0; i < operation.getContent().length(); i++) {
                        // Use operation's client ID and timestamp to create unique char IDs
                        newCharIds.add(operation.getClientId() + ":" + operation.getTimestamp() + ":" + i);
                    }
                    
                    // Insert the new character IDs at the correct position
                    charIds.addAll(position, newCharIds);
                    
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
                        // Delete from the document
                        document.delete(position, endPos);
                        
                        // Also delete the corresponding character IDs
                        int charIdsToDelete = endPos - position;
                        if (position < charIds.size()) {
                            for (int i = 0; i < charIdsToDelete && position < charIds.size(); i++) {
                                charIds.remove(position);
                            }
                        }
                        
                        System.out.println("Applied DELETE at position " + position +
                                ", deleted: '" + contentToDelete + "', Document now: " + document);
                    } else {
                        System.out.println("Warning: Content mismatch for DELETE. Expected: '" +
                                contentToDelete + "', Actual: '" + actualContent + "'");
                        
                        // Try to find and delete the exact content by checking the path
                        boolean deleted = false;
                        for (String pathEntry : operation.getPath()) {
                            if (pathEntry.startsWith("char-")) {
                                String charId = pathEntry.substring(5);
                                int index = charIds.indexOf(charId);
                                if (index >= 0 && index < document.length()) {
                                    document.deleteCharAt(index);
                                    charIds.remove(index);
                                    deleted = true;
                                }
                            }
                        }
                        
                        if (!deleted) {
                            // Fallback to deleting at the position for simplicity
                            document.delete(position, endPos);
                            
                            // Also delete the corresponding character IDs
                            int charIdsToDelete = endPos - position;
                            if (position < charIds.size()) {
                                for (int i = 0; i < charIdsToDelete && position < charIds.size(); i++) {
                                    charIds.remove(position);
                                }
                            }
                        }
                        
                        System.out.println("Applied DELETE, Document now: " + document);
                    }
                } else {
                    System.out.println("Invalid position for DELETE: " + position +
                            " (document length: " + document.length() + ")");
                }
                break;
        }
    }

    private int calculatePositionFromPath(String document, List<String> charIds, List<String> path) {
        if (path == null || path.isEmpty()) return 0;

        if (path.contains("start")) return 0;
        if (path.contains("end")) return document.length();
        
        // Check for character ID references in the path
        for (String pathEntry : path) {
            if (pathEntry.startsWith("char-")) {
                String charId = pathEntry.substring(5);
                int index = charIds.indexOf(charId);
                if (index != -1) {
                    return index;
                }
            } else if (pathEntry.startsWith("after-")) {
                String charId = pathEntry.substring(6);
                int index = charIds.indexOf(charId);
                if (index != -1) {
                    return index + 1;
                }
            }
        }

        // Fall back to the original path calculation
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