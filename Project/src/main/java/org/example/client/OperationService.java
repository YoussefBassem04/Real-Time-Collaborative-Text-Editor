package org.example.client;

import org.example.crdt.Operation;
import org.example.model.EditorMessage;
import javafx.scene.control.TextArea;
import javafx.scene.input.KeyCode;
import javafx.animation.Timeline;
import javafx.util.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

public class OperationService {
    private static final int OPERATION_FLUSH_DELAY = 100;
    private static final int BATCH_DELAY = 50;

    private final EditorController controller;
    private final DocumentState documentState;
    private Timeline operationFlushTimer;
    private Timeline batchTimer;
    private final List<Integer> pendingDeletePositions = new ArrayList<>();
    private final List<Integer> pendingDeleteLengths = new ArrayList<>();
    private TextArea textArea;

    public OperationService(EditorController controller) {
        this.controller = controller;
        this.documentState = controller.getDocumentState();
        initializeTimers();
    }

    private void initializeTimers() {
        operationFlushTimer = new Timeline(
                new javafx.animation.KeyFrame(Duration.millis(OPERATION_FLUSH_DELAY),
                        e -> flushOperations())
        );
        operationFlushTimer.setCycleCount(Timeline.INDEFINITE);
        operationFlushTimer.play();

        batchTimer = new Timeline(
                new javafx.animation.KeyFrame(Duration.millis(BATCH_DELAY * 2),
                        e -> processPendingEdits())
        );
        batchTimer.setCycleCount(1);
    }

    public void setupKeyHandling(TextArea textArea) {
        this.textArea = textArea;
        final AtomicBoolean isBackspaceHeld = new AtomicBoolean(false);

        textArea.setOnKeyPressed(event -> {
            if (event.isControlDown()) {
                switch (event.getCode()) {
                    case Z:
                        controller.performUndo();
                        event.consume();
                        break;
                    case Y:
                        controller.performRedo();
                        event.consume();
                        break;
                }
            }

            if (event.getCode() == KeyCode.BACK_SPACE) {
                isBackspaceHeld.set(true);
                operationFlushTimer.stop();
                operationFlushTimer.setRate(2.0);
                operationFlushTimer.play();
            }
        });

        textArea.setOnKeyReleased(event -> {
            if (event.getCode() == KeyCode.BACK_SPACE) {
                isBackspaceHeld.set(false);
                operationFlushTimer.stop();
                operationFlushTimer.setRate(1.0);
                operationFlushTimer.play();
                processPendingEdits();
            }
        });
    }

    public void handleLocalChange(String oldText, String newText) {
        if (oldText == null || newText == null) return;

        int[] diffInfo = calculateTextDifference(oldText, newText);
        int position = diffInfo[0];
        int deletedLength = diffInfo[1];
        int insertedLength = diffInfo[2];

        if (deletedLength > 0) {
            synchronized (pendingDeletePositions) {
                boolean merged = false;
                for (int i = 0; i < pendingDeletePositions.size(); i++) {
                    int existingPos = pendingDeletePositions.get(i);
                    if (existingPos == position + deletedLength) {
                        pendingDeletePositions.set(i, position);
                        pendingDeleteLengths.set(i, pendingDeleteLengths.get(i) + deletedLength);
                        merged = true;
                        break;
                    } else if (position == existingPos + pendingDeleteLengths.get(i)) {
                        pendingDeleteLengths.set(i, pendingDeleteLengths.get(i) + deletedLength);
                        merged = true;
                        break;
                    }
                }

                if (!merged) {
                    pendingDeletePositions.add(position);
                    pendingDeleteLengths.add(deletedLength);
                }

                if (batchTimer.getStatus() == Timeline.Status.RUNNING) {
                    batchTimer.stop();
                }
                batchTimer.play();
            }
        }

        if (insertedLength > 0) {
            String insertedText = newText.substring(position, position + insertedLength);
            handleLocalInsert(insertedText, position);
        }
    }

    private int[] calculateTextDifference(String oldText, String newText) {
        int commonPrefixLength = 0;
        int minLength = Math.min(oldText.length(), newText.length());

        while (commonPrefixLength < minLength &&
                oldText.charAt(commonPrefixLength) == newText.charAt(commonPrefixLength)) {
            commonPrefixLength++;
        }

        int oldIndex = oldText.length() - 1;
        int newIndex = newText.length() - 1;
        int commonSuffixLength = 0;

        while (oldIndex >= commonPrefixLength &&
                newIndex >= commonPrefixLength &&
                oldText.charAt(oldIndex) == newText.charAt(newIndex)) {
            oldIndex--;
            newIndex--;
            commonSuffixLength++;
        }

        int deletedLength = oldText.length() - commonPrefixLength - commonSuffixLength;
        int insertedLength = newText.length() - commonPrefixLength - commonSuffixLength;

        return new int[] { commonPrefixLength, deletedLength, insertedLength };
    }

    private void handleLocalInsert(String text, int position) {
        List<String> charIds = new ArrayList<>();
        long timestamp = System.currentTimeMillis();
        for (int i = 0; i < text.length(); i++) {
            charIds.add(documentState.getClientId() + ":" + timestamp + ":" + i);
        }

        List<String> path = new ArrayList<>();
        if (position == 0) {
            path.add("start");
        } else if (position >= documentState.getCharacterIds().size()) {
            path.add("end");
        } else {
            path.add("after-" + documentState.getCharacterIds().get(position - 1));
        }

        Operation operation = new Operation(
                Operation.Type.INSERT,
                text,
                path,
                timestamp,
                documentState.getClientId()
        );

        // Save the operation for undo
        if (!controller.getUndoRedoService().isUndoRedoOperation()) {
            controller.getUndoRedoService().saveOperation(operation);
        }

        documentState.getOperationQueue().add(operation);
        documentState.getCharacterIds().addAll(position, charIds);
        documentState.setPreviousContent(textArea.getText());
    }

    private void processPendingEdits() {
        synchronized (pendingDeletePositions) {
            if (pendingDeletePositions.isEmpty()) return;

            Map<Integer, Integer> mergedDeletes = new TreeMap<>(Collections.reverseOrder());
            for (int i = 0; i < pendingDeletePositions.size(); i++) {
                int position = pendingDeletePositions.get(i);
                int length = pendingDeleteLengths.get(i);
                mergedDeletes.put(position, mergedDeletes.getOrDefault(position, 0) + length);
            }

            for (Map.Entry<Integer, Integer> entry : mergedDeletes.entrySet()) {
                handleLocalDelete(entry.getKey(), entry.getValue());
            }

            pendingDeletePositions.clear();
            pendingDeleteLengths.clear();
        }
    }

    private void handleLocalDelete(int position, int length) {
        int endPos = Math.min(position + length, documentState.getCharacterIds().size());
        int actualLength = endPos - position;

        List<String> deletedCharIds = new ArrayList<>();
        for (int i = position; i < endPos; i++) {
            if (i < documentState.getCharacterIds().size()) {
                deletedCharIds.add(documentState.getCharacterIds().get(i));
            }
        }

        List<String> path = new ArrayList<>();
        for (String charId : deletedCharIds) {
            path.add("char-" + charId);
        }

        String deletedContent = documentState.getPreviousContent().substring(position,
                Math.min(position + actualLength, documentState.getPreviousContent().length()));

        Operation operation = new Operation(
                Operation.Type.DELETE,
                deletedContent,
                path,
                System.currentTimeMillis(),
                documentState.getClientId()
        );

        // Save the operation for undo
        if (!controller.getUndoRedoService().isUndoRedoOperation()) {
            controller.getUndoRedoService().saveOperation(operation);
        }

        documentState.getOperationQueue().add(operation);
        documentState.getCharacterIds().subList(position, endPos).clear();
        documentState.setPreviousContent(textArea.getText());
    }

    private void flushOperations() {
        Operation op = documentState.getOperationQueue().poll();
        if (op != null) {
            controller.getNetworkService().sendOperation(op);
        }
    }

    public void handleRemoteMessage(EditorMessage message) {
        if (!documentState.getDocumentId().equals(message.getDocumentId())) return;

        documentState.setProcessingRemoteOperation(true);
        try {
            if (message.getType() == EditorMessage.MessageType.OPERATION) {
                Operation op = message.getOperation();
                if (!op.getClientId().equals(documentState.getClientId())) {
                    if (op.getType() == Operation.Type.INSERT) {
                        processRemoteInsert(op);
                    } else if (op.getType() == Operation.Type.DELETE) {
                        processRemoteDelete(op);
                    } else if (op.getType() == Operation.Type.SYNC) {
                        // Handle sync operations from other clients
                        processSyncOperation(op);
                    }
                }
            } else if (message.getType() == EditorMessage.MessageType.SYNC_RESPONSE) {
                processSyncResponse(message);
            }
        } finally {
            documentState.setProcessingRemoteOperation(false);
        }
    }

    /**
     * Apply a local insert operation generated during undo/redo
     */
    public void applyLocalInsert(Operation op) {
        int pos = calculatePositionFromPath(op.getPath());
        pos = Math.max(0, Math.min(pos, textArea.getText().length()));

        List<String> charIds = new ArrayList<>();
        for (int i = 0; i < op.getContent().length(); i++) {
            charIds.add(op.getClientId() + ":" + op.getTimestamp() + ":" + i);
        }

        String currentText = textArea.getText();
        String newText = currentText.substring(0, pos) + op.getContent() + currentText.substring(pos);
        textArea.setText(newText);
        documentState.getCharacterIds().addAll(pos, charIds);
        documentState.setPreviousContent(newText);
    }

    /**
     * Apply a local delete operation generated during undo/redo
     */
    public void applyLocalDelete(Operation op) {
        Set<Integer> positionsToDelete = new TreeSet<>(Collections.reverseOrder());
        for (String pathEntry : op.getPath()) {
            if (pathEntry.startsWith("char-")) {
                String charId = pathEntry.substring(5);
                int index = documentState.getCharacterIds().indexOf(charId);
                if (index != -1) {
                    positionsToDelete.add(index);
                }
            }
        }

        StringBuilder sb = new StringBuilder(textArea.getText());
        for (int pos : positionsToDelete) {
            if (pos >= 0 && pos < sb.length()) {
                sb.deleteCharAt(pos);
                if (pos < documentState.getCharacterIds().size()) {
                    documentState.getCharacterIds().remove(pos);
                }
            }
        }
        textArea.setText(sb.toString());
        documentState.setPreviousContent(sb.toString());
    }

    private void processRemoteInsert(Operation op) {
        int pos = calculatePositionFromPath(op.getPath());
        pos = Math.max(0, Math.min(pos, textArea.getText().length()));

        List<String> charIds = new ArrayList<>();
        for (int i = 0; i < op.getContent().length(); i++) {
            charIds.add(op.getClientId() + ":" + op.getTimestamp() + ":" + i);
        }

        String currentText = textArea.getText();
        String newText = currentText.substring(0, pos) + op.getContent() + currentText.substring(pos);
        textArea.setText(newText);
        documentState.getCharacterIds().addAll(pos, charIds);
        documentState.setPreviousContent(newText);
    }

    private void processRemoteDelete(Operation op) {
        Set<Integer> positionsToDelete = new TreeSet<>(Collections.reverseOrder());
        for (String pathEntry : op.getPath()) {
            if (pathEntry.startsWith("char-")) {
                String charId = pathEntry.substring(5);
                int index = documentState.getCharacterIds().indexOf(charId);
                if (index != -1) {
                    positionsToDelete.add(index);
                }
            }
        }

        StringBuilder sb = new StringBuilder(textArea.getText());
        for (int pos : positionsToDelete) {
            if (pos >= 0 && pos < sb.length()) {
                sb.deleteCharAt(pos);
                if (pos < documentState.getCharacterIds().size()) {
                    documentState.getCharacterIds().remove(pos);
                }
            }
        }
        textArea.setText(sb.toString());
        documentState.setPreviousContent(sb.toString());
    }

    private void processSyncOperation(Operation op) {
        if (op.getContent() != null) {
            textArea.setText(op.getContent());

            if (op.getCharacterIds() != null && !op.getCharacterIds().isEmpty()) {
                documentState.getCharacterIds().clear();
                documentState.getCharacterIds().addAll(op.getCharacterIds());
            } else {
                // Generate new character IDs if none provided
                documentState.getCharacterIds().clear();
                for (int i = 0; i < op.getContent().length(); i++) {
                    documentState.getCharacterIds().add("sync:" + op.getTimestamp() + ":" + i);
                }
            }
            documentState.setPreviousContent(op.getContent());
        } else {
            textArea.setText("");
            documentState.getCharacterIds().clear();
            documentState.setPreviousContent("");
        }
    }

    private void processSyncResponse(EditorMessage message) {
        String content = message.getContent();
        if (content != null) {
            textArea.setText(content);
            if (message.getCharacterIds() != null && !message.getCharacterIds().isEmpty()) {
                documentState.getCharacterIds().clear();
                documentState.getCharacterIds().addAll(message.getCharacterIds());
            } else {
                documentState.getCharacterIds().clear();
                for (int i = 0; i < content.length(); i++) {
                    documentState.getCharacterIds().add("sync:" + System.currentTimeMillis() + ":" + i);
                }
            }
            documentState.setPreviousContent(content);
        } else {
            textArea.setText("");
            documentState.getCharacterIds().clear();
            documentState.setPreviousContent("");
        }
    }

    public int calculatePositionFromPath(List<String> path) {
        if (path == null || path.isEmpty()) return 0;
        if (path.contains("start")) return 0;
        if (path.contains("end")) return documentState.getCharacterIds().size();

        for (String pathEntry : path) {
            if (pathEntry.startsWith("after-")) {
                String charId = pathEntry.substring(6);
                int index = documentState.getCharacterIds().indexOf(charId);
                if (index != -1) {
                    return index + 1;
                }
            }
        }

        for (String pathEntry : path) {
            if (pathEntry.startsWith("char-")) {
                String charId = pathEntry.substring(5);
                int index = documentState.getCharacterIds().indexOf(charId);
                if (index != -1) {
                    return index;
                }
            }
        }

        return documentState.getCharacterIds().size();
    }

    public int getCaretPosition() {
        return textArea.getCaretPosition();
    }
}