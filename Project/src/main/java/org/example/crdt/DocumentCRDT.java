package org.example.crdt;

import java.util.*;

/**
 * A tree-based CRDT implementation for a collaborative document editor.
 * This provides higher-level functionality for working with the TreeCRDT
 * and handling document operations.
 */
public class DocumentCRDT {
    private final TreeBasedCRDT treeCRDT;
    private final String clientId;
    private final List<Operation> history;

    /**
     * Creates a new document CRDT with the specified client ID
     *
     * @param clientId The unique client identifier
     */
    public DocumentCRDT(String clientId) {
        this.clientId = clientId;
        this.treeCRDT = new TreeBasedCRDT(clientId);
        this.history = new ArrayList<>();
    }

    /**
     * Inserts text at the specified position
     *
     * @param position The position to insert at
     * @param text The text to insert
     * @return List of operations created for this insertion
     */
    public List<Operation> insert(int position, String text) {
        List<Operation> operations = new ArrayList<>();

        for (int i = 0; i < text.length(); i++) {
            Operation op = treeCRDT.insert(position + i, text.charAt(i));
            operations.add(op);
            history.add(op);
        }

        return operations;
    }

    /**
     * Deletes text at the specified range
     *
     * @param start The start position (inclusive)
     * @param end The end position (exclusive)
     * @return List of operations created for this deletion
     */
    public List<Operation> delete(int start, int end) {
        List<Operation> operations = new ArrayList<>();

        // Delete characters in reverse order to avoid index shifting
        for (int i = end - 1; i >= start; i--) {
            Operation op = treeCRDT.delete(i);
            operations.add(op);
            history.add(op);
        }

        return operations;
    }

    /**
     * Applies a remote operation to this document
     *
     * @param operation The operation to apply
     * @return True if the operation was successfully applied
     */
    public boolean applyOperation(Operation operation) {
        // Skip operations from this client as they are already applied
        if (operation.getClientId().equals(clientId)) {
            return true;
        }

        boolean success = treeCRDT.applyOperation(operation);
        if (success) {
            history.add(operation);
        }
        return success;
    }

    /**
     * Gets the current document content
     *
     * @return The document content as a string
     */
    public String getContent() {
        return treeCRDT.getContent();
    }

    /**
     * Gets the operation history for this document
     *
     * @return The list of operations in history
     */
    public List<Operation> getHistory() {
        return Collections.unmodifiableList(history);
    }

    /**
     * Calculates the position in the document from a CRDT path
     *
     * @param path The path from the operation
     * @return The index position in the document
     */
    public int calculatePositionFromPath(List<String> path) {
        String content = getContent();
        // This is a simple implementation - for a more robust solution,
        // you would need to convert the path to a Position and find its index
        // in the TreeCRDT's sorted map

        // For now, we return a placeholder value
        if (path.contains("start")) return 0;
        if (path.contains("end")) return content.length();

        // This is where you would implement your path to position logic
        // using the actual tree structure

        return 0; // Placeholder
    }
}