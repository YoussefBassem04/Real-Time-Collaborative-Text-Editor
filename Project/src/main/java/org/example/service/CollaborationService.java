package org.example.service;

import org.example.crdt.Operation;
import org.example.model.EditorMessage;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Collections;
import java.util.TreeSet;
import java.util.Set;

@Service
public class CollaborationService {

    // Map of documentId -> document content
    private final Map<String, StringBuilder> documentContents = new ConcurrentHashMap<>();

    // Map of clientId -> documentId (to track which document each client is editing)
    private final Map<String, String> clientDocumentMap = new ConcurrentHashMap<>();

    // Keeping track of all operations for possible conflict resolution
    private final Map<String, List<Operation>> documentOperations = new ConcurrentHashMap<>();

    // Map of documentId -> list of character IDs for CRDT
    private final Map<String, List<String>> documentCharacterIds = new ConcurrentHashMap<>();

    // Synchronization objects for each document
    private final Map<String, Object> documentLocks = new ConcurrentHashMap<>();

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

        // Get or create document lock
        Object documentLock = documentLocks.computeIfAbsent(documentId, k -> new Object());

        synchronized (documentLock) {
            // Initialize document content if it doesn't exist
            documentContents.putIfAbsent(documentId, new StringBuilder());
            StringBuilder currentContent = documentContents.get(documentId);

            // Initialize character IDs list if it doesn't exist
            documentCharacterIds.putIfAbsent(documentId, new ArrayList<>());
            List<String> charIds = documentCharacterIds.get(documentId);

            switch (message.getType()) {
                case SYNC_REQUEST:
                    return createSyncResponse(documentId, currentContent);

                case OPERATION:
                    Operation operation = message.getOperation();
                    if (operation != null) {
                        // Track operation for this document
                        documentOperations.computeIfAbsent(documentId, k -> new ArrayList<>());
                        documentOperations.get(documentId).add(operation);

                        // Apply operation to document state
                        applyOperation(documentId, operation);

                        // Return the operation message to broadcast to all clients
                        return message;
                    }
                    break;
            }
        }

        return null;
    }

    private EditorMessage createSyncResponse(String documentId, StringBuilder content) {
        EditorMessage syncResponse = new EditorMessage();
        syncResponse.setType(EditorMessage.MessageType.SYNC_RESPONSE);
        syncResponse.setClientId("SERVER");
        syncResponse.setDocumentId(documentId);
        syncResponse.setContent(content.toString());

        // Include character IDs in the response
        if (documentCharacterIds.containsKey(documentId)) {
            syncResponse.setCharacterIds(new ArrayList<>(documentCharacterIds.get(documentId)));
        }

        System.out.println("Sending SYNC_RESPONSE for document " + documentId +
                " with content length: " + content.length() +
                " and " + (documentCharacterIds.getOrDefault(documentId, Collections.emptyList()).size()) + " character IDs");
        return syncResponse;
    }

    private void applyOperation(String documentId, Operation operation) {
        StringBuilder document = documentContents.get(documentId);
        List<String> charIds = documentCharacterIds.get(documentId);

        switch (operation.getType()) {
            case INSERT:
                processInsertOperation(document, charIds, operation);
                break;

            case DELETE:
                processDeleteOperation(document, charIds, operation);
                break;
        }

        // Validate document state after operation
        validateDocumentState(documentId);
    }

    private void validateDocumentState(String documentId) {
        StringBuilder document = documentContents.get(documentId);
        List<String> charIds = documentCharacterIds.get(documentId);

        if (document.length() != charIds.size()) {
            System.err.println("WARNING: Document state inconsistency detected!");
            System.err.println("Document length: " + document.length() + ", Character IDs count: " + charIds.size());

            // Fix the inconsistency by regenerating character IDs
            charIds.clear();
            for (int i = 0; i < document.length(); i++) {
                charIds.add("server:recovery:" + System.currentTimeMillis() + ":" + i);
            }

            System.out.println("Document state restored with " + charIds.size() + " character IDs");
        }
    }
    private void processInsertOperation(StringBuilder document, List<String> charIds, Operation operation) {
        try {
            // Calculate position from path
            int position = calculatePositionFromPath(document.toString(), charIds, operation.getPath());
            position = Math.max(0, Math.min(position, document.length()));

            // Validate content
            String contentToInsert = operation.getContent();
            if (contentToInsert == null || contentToInsert.isEmpty()) {
                System.out.println("Empty content for INSERT operation, ignoring");
                return;
            }

            // Apply insert to document
            document.insert(position, contentToInsert);

            // Generate and insert character IDs
            List<String> newCharIds = new ArrayList<>();
            for (int i = 0; i < contentToInsert.length(); i++) {
                newCharIds.add(operation.getClientId() + ":" + operation.getTimestamp() + ":" + i);
            }

            // Insert the new character IDs at the correct position
            charIds.addAll(position, newCharIds);

            System.out.println("Applied INSERT at position " + position +
                    ", added " + contentToInsert.length() + " characters" +
                    ", Document length now: " + document.length());
        } catch (Exception e) {
            System.err.println("Error processing insert operation: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void processDeleteOperation(StringBuilder document, List<String> charIds, Operation operation) {
        try {
            Set<Integer> positionsToDelete = new TreeSet<>(Collections.reverseOrder());

            // Extract character IDs from the path
            for (String pathEntry : operation.getPath()) {
                if (pathEntry.startsWith("char-")) {
                    String charId = pathEntry.substring(5);
                    int index = charIds.indexOf(charId);
                    if (index != -1 && index < document.length()) {
                        positionsToDelete.add(index);
                    }
                }
            }

            if (positionsToDelete.isEmpty()) {
                System.out.println("No valid positions found for DELETE operation");
                return;
            }

            // Delete in reverse order to avoid index shifting problems
            for (int pos : positionsToDelete) {
                if (pos >= 0 && pos < document.length()) {
                    document.deleteCharAt(pos);
                    if (pos < charIds.size()) {
                        charIds.remove(pos);
                    }
                }
            }

            System.out.println("Applied DELETE for " + positionsToDelete.size() +
                    " characters, Document length now: " + document.length());
        } catch (Exception e) {
            System.err.println("Error processing delete operation: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private int calculatePositionFromPath(String document, List<String> charIds, List<String> path) {
        if (path == null || path.isEmpty()) return 0;

        if (path.contains("start")) return 0;
        if (path.contains("end")) return document.length();

        // First try after-X paths
        for (String pathEntry : path) {
            if (pathEntry.startsWith("after-")) {
                String charId = pathEntry.substring(6);
                int index = charIds.indexOf(charId);
                if (index != -1) {
                    return Math.min(index + 1, document.length());
                }
            }
        }

        // Then try direct char-X paths
        for (String pathEntry : path) {
            if (pathEntry.startsWith("char-")) {
                String charId = pathEntry.substring(5);
                int index = charIds.indexOf(charId);
                if (index != -1) {
                    return Math.min(index, document.length());
                }
            }
        }

        // Default to end of document if path can't be resolved
        return document.length();
    }

    public void handleClientDisconnect(String clientId) {
        // Remove from client-document mapping
        clientDocumentMap.remove(clientId);
        System.out.println("Client disconnected: " + clientId);
    }
}