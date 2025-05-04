package org.example.client;

import org.example.crdt.Operation;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.HashMap;
import java.util.Map;

public class UndoRedoService {
    private static final int MAX_UNDO_HISTORY = 100;

    // Stacks to store operations for undo/redo
    private final Deque<Operation> undoStack = new ArrayDeque<>();
    private final Deque<Operation> redoStack = new ArrayDeque<>();

    // Map to store deleted character positions for better undo reconstruction
    private final Map<String, Integer> characterPositionCache = new HashMap<>();

    private final EditorController controller;
    private boolean isUndoRedoOperation = false;
    boolean isApplyingOperation = false;

    public UndoRedoService(EditorController controller) {
        this.controller = controller;
    }

    /**
     * Performs an undo by inverting the last operation performed by this client
     */
    public void performUndo() {
        if (undoStack.isEmpty()) return;

        isUndoRedoOperation = true;

        // Get the last operation performed by this client
        Operation lastOp = undoStack.pop();

        // Create and apply the inverse operation
        Operation inverseOp = createInverseOperation(lastOp);

        // Add the original operation to the redo stack
        redoStack.push(lastOp);

        // Apply the inverse operation locally and send to server
        applyAndSendOperation(inverseOp);

        isUndoRedoOperation = false;
    }

    /**
     * Performs a redo by reapplying the last undone operation
     */
    public void performRedo() {
        if (redoStack.isEmpty()) return;

        isUndoRedoOperation = true;

        // Get the operation to redo
        Operation redoOp = redoStack.pop();

        // Add it back to the undo stack
        undoStack.push(redoOp);

        // Apply the operation locally and send to server
        applyAndSendOperation(redoOp);

        isUndoRedoOperation = false;
    }

    /**
     * Creates an inverse operation that will undo the effect of the given operation
     */
    private Operation createInverseOperation(Operation op) {
        if (op.getType() == Operation.Type.INSERT) {
            // For insert operations, create a delete operation
            return new Operation(
                    Operation.Type.DELETE,
                    op.getContent(),
                    generateDeletePaths(op),
                    System.currentTimeMillis(),
                    controller.getDocumentState().getClientId()
            );
        }
        else if (op.getType() == Operation.Type.DELETE) {
            // For delete operations, create an insert operation
            List<String> path = determineInsertPathForUndo(op);

            return new Operation(
                    Operation.Type.INSERT,
                    op.getContent(),
                    path,
                    System.currentTimeMillis(),
                    controller.getDocumentState().getClientId()
            );
        }

        // For any other operation type, return a no-op
        return op;
    }

    /**
     * Generates the proper path for a delete operation that will undo an insert
     */
    private List<String> generateDeletePaths(Operation insertOp) {
        List<String> deletePaths = new ArrayList<>();

        // Generate character IDs for the inserted content
        String clientId = insertOp.getClientId();
        long timestamp = insertOp.getTimestamp();

        // For each character in the inserted content, generate the expected character ID
        for (int i = 0; i < insertOp.getContent().length(); i++) {
            String charId = clientId + ":" + timestamp + ":" + i;
            deletePaths.add("char-" + charId);
        }

        return deletePaths;
    }

    /**
     * Determines the appropriate insert path for undoing a delete operation
     */
    private List<String> determineInsertPathForUndo(Operation deleteOp) {
        List<String> path = new ArrayList<>();

        // Check if we have position info from the delete paths
        if (deleteOp.getPath() != null && !deleteOp.getPath().isEmpty()) {
            // Try to extract positional information from the delete paths

            // If the first character was deleted, the content should be inserted at the start
            if (deleteOp.getPath().get(0).contains(":0")) {
                // Check if this was the very first character in the document
                boolean wasFirst = true;
                for (String pathEntry : deleteOp.getPath()) {
                    if (pathEntry.startsWith("char-")) {
                        String charId = pathEntry.substring(5);
                        Integer cachedPos = characterPositionCache.get(charId);
                        if (cachedPos != null && cachedPos > 0) {
                            wasFirst = false;
                            break;
                        }
                    }
                }

                if (wasFirst) {
                    path.add("start");
                    return path;
                }
            }

            // Try to find a reference character that still exists in the document
            for (String pathEntry : deleteOp.getPath()) {
                if (pathEntry.startsWith("char-")) {
                    String charId = pathEntry.substring(5);

                    // Parse the character ID to identify related characters
                    String[] parts = charId.split(":");
                    if (parts.length == 3) {
                        String originalClientId = parts[0];
                        String timestamp = parts[1];
                        int index = Integer.parseInt(parts[2]);

                        // Try to find a character before this one that still exists
                        if (index > 0) {
                            String prevCharId = originalClientId + ":" + timestamp + ":" + (index - 1);
                            int prevPos = controller.getDocumentState().getCharacterIds().indexOf(prevCharId);
                            if (prevPos != -1) {
                                path.add("after-" + prevCharId);
                                return path;
                            }
                        }

                        // Try to find a character after this one that still exists
                        String nextCharId = originalClientId + ":" + timestamp + ":" + (index + 1);
                        int nextPos = controller.getDocumentState().getCharacterIds().indexOf(nextCharId);
                        if (nextPos != -1) {
                            // Insert before the next character
                            if (nextPos > 0) {
                                String beforeNextId = controller.getDocumentState().getCharacterIds().get(nextPos - 1);
                                path.add("after-" + beforeNextId);
                                return path;
                            } else {
                                path.add("start");
                                return path;
                            }
                        }
                    }
                }
            }
        }

        // If we couldn't determine a precise position, use the current caret position as a fallback
        int caretPos = controller.getOperationService().getCaretPosition();
        if (caretPos == 0) {
            path.add("start");
        } else if (caretPos >= controller.getDocumentState().getCharacterIds().size()) {
            path.add("end");
        } else {
            String prevCharId = controller.getDocumentState().getCharacterIds().get(caretPos - 1);
            path.add("after-" + prevCharId);
        }

        return path;
    }

    /**
     * Applies an operation locally and sends it to the server
     */
    private void applyAndSendOperation(Operation op) {
        isApplyingOperation = true;

        // Apply the operation locally first
        if (op.getType() == Operation.Type.INSERT) {
            controller.getOperationService().applyLocalInsert(op);
        } else if (op.getType() == Operation.Type.DELETE) {
            controller.getOperationService().applyLocalDelete(op);
        }

        // Send the operation to the server
        controller.getNetworkService().sendOperation(op);

        isApplyingOperation = false;
    }

    /**
     * Saves an operation to the undo stack
     * Called when a local operation is performed
     */
    public void saveOperation(Operation op) {
        if (isUndoRedoOperation || isApplyingOperation) return;

        // Save character positions for delete operations for better undo reconstruction
        if (op.getType() == Operation.Type.DELETE) {
            for (String pathEntry : op.getPath()) {
                if (pathEntry.startsWith("char-")) {
                    String charId = pathEntry.substring(5);
                    int index = controller.getDocumentState().getCharacterIds().indexOf(charId);
                    if (index != -1) {
                        characterPositionCache.put(charId, index);
                    }
                }
            }
        }

        undoStack.push(op);
        redoStack.clear();

        if (undoStack.size() > MAX_UNDO_HISTORY) {
            undoStack.removeLast();
        }
    }

    public boolean isUndoRedoOperation() {
        return isUndoRedoOperation;
    }

    public boolean isApplyingOperation() {
        return isApplyingOperation;
    }
}