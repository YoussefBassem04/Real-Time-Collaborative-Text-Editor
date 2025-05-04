package org.example.client;

import org.example.crdt.Operation;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

public class UndoRedoService {
    private static final int MAX_UNDO_HISTORY = 100;

    // Stacks to store operations for undo/redo
    private final Deque<Operation> undoStack = new ArrayDeque<>();
    private final Deque<Operation> redoStack = new ArrayDeque<>();

    // Map to store deleted character positions for better undo reconstruction
    private final Map<String, Integer> characterPositionCache = new HashMap<>();

    // Map to store original char IDs to new char IDs for redo
    private final Map<String, String> charIdMapping = new HashMap<>();

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

        // Apply the inverse operation and capture new character IDs
        List<String> newCharIds = applyAndSendOperationWithCharIds(inverseOp, lastOp);

        // Map original char IDs to new char IDs for redo
        if (lastOp.getType() == Operation.Type.DELETE && !newCharIds.isEmpty()) {
            List<String> originalCharIds = lastOp.getPath().stream()
                    .filter(p -> p.startsWith("char-"))
                    .map(p -> p.substring(5))
                    .collect(Collectors.toList());
            for (int i = 0; i < Math.min(originalCharIds.size(), newCharIds.size()); i++) {
                charIdMapping.put(originalCharIds.get(i), newCharIds.get(i));
            }
        }

        // Add the original operation to the redo stack
        redoStack.push(lastOp);

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

        // Update DELETE operation paths with new character IDs
        if (redoOp.getType() == Operation.Type.DELETE) {
            List<String> updatedPath = new ArrayList<>();
            for (String pathEntry : redoOp.getPath()) {
                if (pathEntry.startsWith("char-")) {
                    String oldCharId = pathEntry.substring(5);
                    String newCharId = charIdMapping.getOrDefault(oldCharId, oldCharId);
                    updatedPath.add("char-" + newCharId);
                } else {
                    updatedPath.add(pathEntry);
                }
            }
            redoOp = new Operation(
                    redoOp.getType(),
                    redoOp.getContent(),
                    updatedPath,
                    redoOp.getTimestamp(),
                    redoOp.getClientId()
            );
        }

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

        // Try to use cached position from characterPositionCache
        if (deleteOp.getPath() != null && !deleteOp.getPath().isEmpty()) {
            String firstCharId = null;
            for (String pathEntry : deleteOp.getPath()) {
                if (pathEntry.startsWith("char-")) {
                    firstCharId = pathEntry.substring(5);
                    break;
                }
            }

            if (firstCharId != null) {
                Integer cachedPos = characterPositionCache.get(firstCharId);
                if (cachedPos != null && cachedPos >= 0 && cachedPos <= controller.getDocumentState().getCharacterIds().size()) {
                    if (cachedPos == 0) {
                        path.add("start");
                    } else {
                        // Use the character ID at the position before the cached position
                        String prevCharId = controller.getDocumentState().getCharacterIds().get(cachedPos - 1);
                        path.add("after-" + prevCharId);
                    }
                    return path;
                }
            }
        }

        // Fallback: Try to find a reference character from the delete operation's path
        for (String pathEntry : deleteOp.getPath()) {
            if (pathEntry.startsWith("char-")) {
                String charId = pathEntry.substring(5);
                String[] parts = charId.split(":");
                if (parts.length == 3) {
                    String originalClientId = parts[0];
                    String timestamp = parts[1];
                    int index = Integer.parseInt(parts[2]);

                    // Try to find the previous character
                    if (index > 0) {
                        String prevCharId = originalClientId + ":" + timestamp + ":" + (index - 1);
                        int prevPos = controller.getDocumentState().getCharacterIds().indexOf(prevCharId);
                        if (prevPos != -1) {
                            path.add("after-" + prevCharId);
                            return path;
                        }
                    }

                    // Try to find the next character
                    String nextCharId = originalClientId + ":" + timestamp + ":" + (index + 1);
                    int nextPos = controller.getDocumentState().getCharacterIds().indexOf(nextCharId);
                    if (nextPos != -1) {
                        if (nextPos > 0) {
                            String beforeNextId = controller.getDocumentState().getCharacterIds().get(nextPos - 1);
                            path.add("after-" + beforeNextId);
                        } else {
                            path.add("start");
                        }
                        return path;
                    }
                }
            }
        }

        // Final fallback: Use the current caret position
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
     * Applies an operation locally and sends it to the server, returning new character IDs
     */
    private List<String> applyAndSendOperationWithCharIds(Operation op, Operation originalOp) {
        isApplyingOperation = true;
        List<String> newCharIds = new ArrayList<>();

        if (op.getType() == Operation.Type.INSERT) {
            int pos = controller.getOperationService().calculatePositionFromPath(op.getPath());
            pos = Math.max(0, Math.min(pos, controller.getOperationService().getTextArea().getText().length()));

            for (int i = 0; i < op.getContent().length(); i++) {
                newCharIds.add(op.getClientId() + ":" + op.getTimestamp() + ":" + i);
            }

            controller.getOperationService().applyLocalInsert(op);
            controller.getDocumentState().getCharacterIds().addAll(pos, newCharIds);

            // Update characterPositionCache for redo
            if (originalOp != null && originalOp.getType() == Operation.Type.DELETE) {
                for (String charId : newCharIds) {
                    characterPositionCache.put(charId, pos);
                }
            }
        } else if (op.getType() == Operation.Type.DELETE) {
            controller.getOperationService().applyLocalDelete(op);
        }

        controller.getNetworkService().sendOperation(op);
        isApplyingOperation = false;
        return newCharIds;
    }

    /**
     * Applies an operation locally and sends it to the server
     */
    private void applyAndSendOperation(Operation op) {
        applyAndSendOperationWithCharIds(op, null);
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