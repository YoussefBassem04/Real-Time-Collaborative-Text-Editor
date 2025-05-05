package org.example.service;

import org.example.crdt.Operation;
import org.example.model.EditorMessage;
import org.json.JSONException;
import org.json.JSONObject;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Collections;
import java.util.HashMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.Set;
import java.util.HashSet;

@Service
public class CollaborationService {

    private final Map<String, String> roomIds = new ConcurrentHashMap<>();
    private final Map<String, String> usernames = new ConcurrentHashMap<>();
    private final Map<String, StringBuilder> documentContents = new ConcurrentHashMap<>();
    private final Map<String, String> clientDocumentMap = new ConcurrentHashMap<>();
    private final Map<String, List<Operation>> documentOperations = new ConcurrentHashMap<>();
    private final Map<String, List<String>> documentCharacterIds = new ConcurrentHashMap<>();
    private final Map<String, Object> documentLocks = new ConcurrentHashMap<>();
    private final Map<String, Permission> clientPermissions = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> documentConnectedUsers = new ConcurrentHashMap<>();
    private final SimpMessagingTemplate messagingTemplate; // Added for broadcasting

    private enum Permission {
        EDIT,
        READ_ONLY
    }

    public CollaborationService(SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
    }

    public Map<String, Object> createNewRoom() {
        String editRoomId = UUID.randomUUID().toString();
        String readOnlyRoomId = UUID.randomUUID().toString();
        roomIds.put(editRoomId, readOnlyRoomId);
        documentConnectedUsers.put(editRoomId, ConcurrentHashMap.newKeySet());
        
        Map<String, Object> response = new HashMap<>();
        response.put("editRoomId", editRoomId);
        response.put("readOnlyRoomId", readOnlyRoomId);
        
        return response;
    }


    public Map<String, Object> joinRoom(String roomId) {
        Map<String, Object> response = new HashMap<>();
        if (roomIds.containsKey(roomId)) {
            response.put("editRoomId", roomId);
            response.put("readOnlyRoomId", roomIds.get(roomId));
            response.put("canEdit", true);
            return response;
        }
        if (roomIds.values().contains(roomId)) {
            //join, but don't edit
            response.put("editRoomId", "You can't edit this");
            response.put("readOnlyRoomId", roomId);
            response.put("canEdit", false);
            return response;
        }
        else {
            //throw exception
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Room with ID " + roomId + " not found");
        }
    }

    public EditorMessage processMessage(EditorMessage message) {
        String documentId = message.getDocumentId();
        if (documentId == null || documentId.isEmpty()) {
            documentId = "default";
            message.setDocumentId(documentId);
        }

        String clientId = message.getClientId();
        System.out.println("Client ID processed:" + clientId);
        System.out.println("Document ID processed:" + documentId);
        if (clientId != null) {
            clientDocumentMap.put(clientId, documentId);
            documentConnectedUsers.computeIfAbsent(documentId, k -> ConcurrentHashMap.newKeySet()).add(clientId);
            broadcastUserList(documentId);
        }
            documentContents.putIfAbsent(documentId, new StringBuilder());
            StringBuilder currentContent = documentContents.get(documentId);

            documentCharacterIds.putIfAbsent(documentId, new ArrayList<>());

            switch (message.getType()) {
                case SYNC_REQUEST:
                    return createSyncResponse(documentId, currentContent);

                case OPERATION:
                    Operation operation = message.getOperation();
                    if (operation != null) {
                        documentOperations.computeIfAbsent(documentId, k -> new ArrayList<>());
                        documentOperations.get(documentId).add(operation);

                        applyOperation(documentId, operation);

                        return message;
                    }
                    break;
                case USER_LIST:
                    return message;
            }
        return null;
    }

    private EditorMessage createSyncResponse(String documentId, StringBuilder content) {
        EditorMessage syncResponse = new EditorMessage();
        syncResponse.setType(EditorMessage.MessageType.SYNC_RESPONSE);
        syncResponse.setClientId("SERVER");
        syncResponse.setDocumentId(documentId);
        syncResponse.setContent(content.toString());

        if (documentCharacterIds.containsKey(documentId)) {
            syncResponse.setCharacterIds(new ArrayList<>(documentCharacterIds.get(documentId)));
        }

        System.out.println("Sending SYNC_RESPONSE for document " + documentId +
                " with content length: " + content.length() +
                " and " + (documentCharacterIds.getOrDefault(documentId, Collections.emptyList()).size()) + " character IDs");
        return syncResponse;
    }

    private void broadcastUserList(String documentId) {
        EditorMessage userListMessage = new EditorMessage();
        userListMessage.setType(EditorMessage.MessageType.USER_LIST);
        userListMessage.setClientId("SERVER");
        userListMessage.setDocumentId(documentId);
        userListMessage.setConnectedUsers(new ArrayList<>(documentConnectedUsers.getOrDefault(documentId, Collections.emptySet())));

        // Broadcast to all clients subscribed to this document's topic
        messagingTemplate.convertAndSend("/topic/editor/" + documentId, userListMessage);
        System.out.println("Broadcasted user list for document " + documentId + ": " + userListMessage.getConnectedUsers());
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

            case SYNC:
                processSyncOperation(documentId, document, charIds, operation);
                break;
        }

        validateDocumentState(documentId);
    }
    private void processSyncOperation(String documentId, StringBuilder document, List<String> charIds, Operation operation) {
        try {
            document.setLength(0);
            if (operation.getContent() != null) {
                document.append(operation.getContent());
            }

            charIds.clear();
            if (operation.getCharacterIds() != null && !operation.getCharacterIds().isEmpty()) {
                charIds.addAll(operation.getCharacterIds());
            } else {
                // Generate new character IDs if none provided
                for (int i = 0; i < document.length(); i++) {
                    charIds.add("sync:" + operation.getTimestamp() + ":" + i);
                }
            }

            System.out.println("Applied SYNC operation from client " + operation.getClientId() +
                    ", document length: " + document.length() +
                    ", char IDs: " + charIds.size());

            // Clear operations for this document to avoid conflicts with the new state
            documentOperations.computeIfAbsent(documentId, k -> new ArrayList<>()).clear();
        } catch (Exception e) {
            System.err.println("Error processing sync operation: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void validateDocumentState(String documentId) {
        StringBuilder document = documentContents.get(documentId);
        List<String> charIds = documentCharacterIds.get(documentId);

        if (document.length() != charIds.size()) {
            System.err.println("WARNING: Document state inconsistency detected!");
            System.err.println("Document length: " + document.length() + ", Character IDs count: " + charIds.size());

            charIds.clear();
            for (int i = 0; i < document.length(); i++) {
                charIds.add("server:recovery:" + System.currentTimeMillis() + ":" + i);
            }

            System.out.println("Document state restored with " + charIds.size() + " character IDs");
        }
    }

    private void processInsertOperation(StringBuilder document, List<String> charIds, Operation operation) {
        try {
            int position = calculatePositionFromPath(document.toString(), charIds, operation.getPath());
            position = Math.max(0, Math.min(position, document.length()));

            String contentToInsert = operation.getContent();
            if (contentToInsert == null || contentToInsert.isEmpty()) {
                System.out.println("Empty content for INSERT operation, ignoring");
                return;
            }

            document.insert(position, contentToInsert);

            List<String> newCharIds = new ArrayList<>();
            for (int i = 0; i < contentToInsert.length(); i++) {
                newCharIds.add(operation.getClientId() + ":" + operation.getTimestamp() + ":" + i);
            }

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

            // Check if this is a mass delete operation (more than 50% of document)
            boolean isMassDelete = positionsToDelete.size() > (document.length() * 0.5);

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

            // If this was a mass delete, perform extra validation
            if (isMassDelete) {
                validateDocumentState(operation.getClientId());
            }
        } catch (Exception e) {
            System.err.println("Error processing delete operation: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private int calculatePositionFromPath(String document, List<String> charIds, List<String> path) {
        if (path == null || path.isEmpty()) return 0;

        if (path.contains("start")) return 0;
        if (path.contains("end")) return document.length();

        for (String pathEntry : path) {
            if (pathEntry.startsWith("after-")) {
                String charId = pathEntry.substring(6);
                int index = charIds.indexOf(charId);
                if (index != -1) {
                    return Math.min(index + 1, document.length());
                }
            }
        }

        for (String pathEntry : path) {
            if (pathEntry.startsWith("char-")) {
                String charId = pathEntry.substring(5);
                int index = charIds.indexOf(charId);
                if (index != -1) {
                    return Math.min(index, document.length());
                }
            }
        }

        System.out.println("WARNING: Could not resolve path " + path + ", defaulting to end of document");
        return document.length();
    }

    public void handleClientDisconnect(String clientId) {
        System.out.println("Document map state:" + clientDocumentMap);
        String documentId = clientDocumentMap.get(clientId);
        clientDocumentMap.remove(clientId);
        System.out.println("DocumentID user disconnected from:" + documentId);
        if (documentId != null) {
            Set<String> connectedUsers = documentConnectedUsers.get(documentId);
            if (connectedUsers != null) {
                connectedUsers.remove(clientId);
                broadcastUserList(documentId);
            }
        }
        System.out.println("Client disconnected: " + clientId);
    }
}