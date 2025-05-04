package org.example.service;

import org.example.crdt.Operation;
import org.example.crdt.TreeBasedCRDT;
import org.example.model.EditorMessage;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class CollaborationService {

    private final Map<String, TreeBasedCRDT> documentCRDTs = new ConcurrentHashMap<>();
    private final Map<String, String> clientDocumentMap = new ConcurrentHashMap<>();

    public EditorMessage processMessage(EditorMessage message) {
        String documentId = message.getDocumentId();
        if (documentId == null || documentId.isEmpty()) {
            documentId = "default";
            message.setDocumentId(documentId);
        }

        String clientId = message.getClientId();
        if (clientId != null) {
            clientDocumentMap.put(clientId, documentId);
        }

        documentCRDTs.putIfAbsent(documentId, new TreeBasedCRDT("SERVER"));
        TreeBasedCRDT crdt = documentCRDTs.get(documentId);

        switch (message.getType()) {
            case SYNC_REQUEST:
                EditorMessage syncResponse = new EditorMessage();
                syncResponse.setType(EditorMessage.MessageType.SYNC_RESPONSE);
                syncResponse.setClientId("SERVER");
                syncResponse.setDocumentId(documentId);
                syncResponse.setContent(crdt.getContent());
                syncResponse.setNodeIds(crdt.getNodeIdsInOrder());
                System.out.println("Sending SYNC_RESPONSE with content: " + crdt.getContent());
                return syncResponse;

            case OPERATION:
                Operation operation = message.getOperation();
                if (operation != null) {
                    List<Operation> adjustedOps = adjustOperation(operation, crdt);
                    List<String> assignedNodeIds = new ArrayList<>();
                    boolean success = true;
                    for (Operation op : adjustedOps) {
                        success &= crdt.applyOperation(op);
                        if (!success) {
                            System.out.println("Failed to apply operation: " + op);
                            break;
                        }
                        if (op.getType() == Operation.Type.INSERT) {
                            assignedNodeIds.add(op.getNodeId());
                        }
                    }
                    if (success) {
                        System.out.println("Applied operations for: " + operation);
                        System.out.println("Document state: " + crdt.getContent());
                        crdt.debugPrintStructure();
                        EditorMessage broadcastMessage = new EditorMessage();
                        broadcastMessage.setType(EditorMessage.MessageType.OPERATION);
                        broadcastMessage.setClientId(operation.getClientId());
                        broadcastMessage.setDocumentId(documentId);
                        broadcastMessage.setOperation(operation);
                        broadcastMessage.setNodeIds(assignedNodeIds);
                        return broadcastMessage;
                    }
                }
                break;
        }

        return null;
    }

    private List<Operation> adjustOperation(Operation operation, TreeBasedCRDT crdt) {
        List<Operation> operations = new ArrayList<>();
        String clientId = operation.getClientId();
        long timestamp = operation.getTimestamp();

        if (operation.getType() == Operation.Type.INSERT) {
            String content = operation.getContent();
            int basePos = 0;
            try {
                if (!operation.getPath().isEmpty()) {
                    String[] parts = operation.getPath().get(0).split(":");
                    basePos = Integer.parseInt(parts[0]);
                }
            } catch (NumberFormatException | IndexOutOfBoundsException e) {
                basePos = crdt.getContent().length();
            }

            for (int i = 0; i < content.length(); i++) {
                String nodeId = clientId + ":" + (timestamp + i);
                List<String> path = new ArrayList<>();
                path.add(basePos + i + ":" + clientId);
                Operation op = new Operation(
                        Operation.Type.INSERT,
                        String.valueOf(content.charAt(i)),
                        path,
                        timestamp,
                        clientId
                );
                op.setNodeId(nodeId);
                operations.add(op);
            }
        } else if (operation.getType() == Operation.Type.DELETE) {
            List<String> path = operation.getPath();
            Operation op = new Operation(
                    Operation.Type.DELETE,
                    operation.getContent(),
                    path,
                    timestamp,
                    clientId
            );
            operations.add(op);
        }

        return operations;
    }

    public void handleClientDisconnect(String clientId) {
        clientDocumentMap.remove(clientId);
        System.out.println("Client disconnected: " + clientId);
    }
}