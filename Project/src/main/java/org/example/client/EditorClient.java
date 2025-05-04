package org.example.client;

import org.example.crdt.Operation;
import org.example.model.EditorMessage;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import org.springframework.web.socket.sockjs.client.SockJsClient;
import org.springframework.web.socket.sockjs.client.Transport;
import org.springframework.web.socket.sockjs.client.WebSocketTransport;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Stage;
import javafx.util.Duration;

import java.lang.reflect.Type;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

public class EditorClient extends Application {
    private TextArea textArea;
    private StompSession stompSession;
    private String clientId;
    private String documentId;
    private AtomicBoolean isProcessingRemoteOperation = new AtomicBoolean(false);
    private final Object documentLock = new Object();
    private String previousContent = "";
    private Label statusLabel;
    private ComboBox<String> documentSelector;

    // Undo/Redo stacks
    private Deque<UndoRedoState> undoStack = new LinkedList<>();
    private Deque<UndoRedoState> redoStack = new LinkedList<>();
    private boolean isUndoRedoOperation = false;

    // Store character IDs for CRDT
    private List<String> characterIds = new ArrayList<>();

    // Queue for batching operations
    private Queue<Operation> operationQueue = new ConcurrentLinkedQueue<>();

    // Connection status
    private StringProperty connectionStatus = new SimpleStringProperty("Disconnected");

    // Rate limiting and batching timers
    private javafx.animation.Timeline operationFlushTimer;
    private javafx.animation.Timeline batchTimer;
    private javafx.animation.Timeline consistencyCheckTimer;

    // Pending edits
    private List<Integer> pendingDeletePositions = new ArrayList<>();
    private List<Integer> pendingDeleteLengths = new ArrayList<>();

    // Constants
    private static final int OPERATION_FLUSH_DELAY = 100; // ms
    private static final int BATCH_DELAY = 50; // ms
    private static final int MAX_UNDO_HISTORY = 100;

    // Previously visited documents
    private Set<String> recentDocuments = new HashSet<>();

    @Override
    public void start(Stage primaryStage) {
        // Initialize client ID
        this.clientId = UUID.randomUUID().toString();

        // Create UI
        createUI(primaryStage);

        // Initialize timers
        initializeTimers();
    }

    private void createUI(Stage primaryStage) {
        BorderPane root = new BorderPane();
        root.setPadding(new Insets(10));

        // Top section - Document selection and client info
        HBox topBar = new HBox(10);
        topBar.setAlignment(Pos.CENTER_LEFT);
        topBar.setPadding(new Insets(5));

        Label docLabel = new Label("Document:");
        documentSelector = new ComboBox<>();
        documentSelector.setEditable(true);
        documentSelector.setPrefWidth(200);
        documentSelector.getItems().add("default");
        documentSelector.setValue("default");
        this.documentId = "default";

        Button connectButton = new Button("Connect");
        connectButton.setOnAction(e -> connectToDocument(documentSelector.getValue()));

        Label clientIdLabel = new Label("Client ID:");
        TextField clientIdField = new TextField(clientId);
        clientIdField.setPrefWidth(220);
        clientIdField.setEditable(false);

        topBar.getChildren().addAll(docLabel, documentSelector, connectButton,
                new Separator(javafx.geometry.Orientation.VERTICAL),
                clientIdLabel, clientIdField);

        // Center - Text editor
        textArea = new TextArea();
        textArea.setWrapText(true);
        textArea.textProperty().addListener((obs, oldText, newText) -> {
            if (!isProcessingRemoteOperation.get() && !isUndoRedoOperation) {
                handleLocalChange(oldText, newText);
            }
        });

        // Bottom - Status bar
        HBox statusBar = new HBox(10);
        statusBar.setAlignment(Pos.CENTER_LEFT);
        statusBar.setPadding(new Insets(5));

        statusLabel = new Label();
        statusLabel.textProperty().bind(connectionStatus);

        Label charCountLabel = new Label();
        textArea.textProperty().addListener((obs, old, newText) ->
                charCountLabel.setText("Characters: " + newText.length()));
        charCountLabel.setText("Characters: 0");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        // Undo/Redo buttons
        Button undoButton = new Button("Undo");
        undoButton.setOnAction(e -> performUndo());
        undoButton.disableProperty().bind(textArea.disabledProperty());

        Button redoButton = new Button("Redo");
        redoButton.setOnAction(e -> performRedo());
        redoButton.disableProperty().bind(textArea.disabledProperty());

        statusBar.getChildren().addAll(statusLabel, spacer, charCountLabel,
                new Separator(javafx.geometry.Orientation.VERTICAL),
                undoButton, redoButton);

        // Set up keyboard shortcuts for undo/redo
        textArea.setOnKeyPressed(event -> {
            if (event.isControlDown()) {
                switch (event.getCode()) {
                    case Z:
                        performUndo();
                        event.consume();
                        break;
                    case Y:
                        performRedo();
                        event.consume();
                        break;
                    default:
                        break;
                }
            }
        });

        // Assemble root layout
        root.setTop(topBar);
        root.setCenter(textArea);
        root.setBottom(statusBar);

        // Create scene and show stage
        Scene scene = new Scene(root, 900, 600);
        primaryStage.setScene(scene);
        primaryStage.setTitle("Collaborative JavaFX Editor");
        primaryStage.setOnCloseRequest(e -> cleanup());
        primaryStage.show();

        // Initial connection
        connectToDocument(documentId);
    }

    private void initializeTimers() {
        // Timer for flushing operations to server
        operationFlushTimer = new javafx.animation.Timeline(
                new javafx.animation.KeyFrame(Duration.millis(OPERATION_FLUSH_DELAY),
                        e -> flushOperations())
        );
        operationFlushTimer.setCycleCount(javafx.animation.Animation.INDEFINITE);
        operationFlushTimer.play();

        // Timer for batching local edits
        batchTimer = new javafx.animation.Timeline(
                new javafx.animation.KeyFrame(Duration.millis(BATCH_DELAY),
                        e -> processPendingEdits())
        );
        batchTimer.setCycleCount(1);

        // Timer for consistency checks
        consistencyCheckTimer = new javafx.animation.Timeline(
                new javafx.animation.KeyFrame(Duration.millis(5000),
                        e -> validateClientState())
        );
        consistencyCheckTimer.setCycleCount(javafx.animation.Animation.INDEFINITE);
        consistencyCheckTimer.play();
    }

    private void connectToDocument(String docId) {
        // Clear editor state
        textArea.setDisable(true);
        connectionStatus.set("Connecting to document: " + docId + "...");

        // Update document ID
        this.documentId = docId;

        // Add to recent documents
        if (!recentDocuments.contains(docId)) {
            recentDocuments.add(docId);
            documentSelector.getItems().add(docId);
        }

        // Reset editor state
        synchronized (documentLock) {
            characterIds.clear();
            undoStack.clear();
            redoStack.clear();
        }

        // Connect to WebSocket
        connectToWebSocket();
    }

    private void connectToWebSocket() {
        List<Transport> transports = new ArrayList<>();
        transports.add(new WebSocketTransport(new StandardWebSocketClient()));
        WebSocketStompClient stompClient = new WebSocketStompClient(new SockJsClient(transports));
        stompClient.setMessageConverter(new MappingJackson2MessageConverter());

        stompClient.connect("ws://localhost:8080/ws", new StompSessionHandlerAdapter() {
            @Override
            public void afterConnected(StompSession session, StompHeaders connectedHeaders) {
                System.out.println("✅ Connected to WebSocket");
                stompSession = session;

                Platform.runLater(() -> {
                    connectionStatus.set("Connected to document: " + documentId);
                    textArea.setDisable(false);
                });

                String topic = "/topic/editor/" + documentId;
                session.subscribe(topic, new StompFrameHandler() {
                    @Override
                    public Type getPayloadType(StompHeaders headers) {
                        return EditorMessage.class;
                    }

                    @Override
                    public void handleFrame(StompHeaders headers, Object payload) {
                        EditorMessage msg = (EditorMessage) payload;
                        System.out.println("⇦ [" + topic + "] Received: " + msg);
                        handleRemoteMessage(msg);
                    }
                });

                EditorMessage syncRequest = new EditorMessage();
                syncRequest.setType(EditorMessage.MessageType.SYNC_REQUEST);
                syncRequest.setClientId(clientId);
                syncRequest.setDocumentId(documentId);
                session.send("/app/editor/operation", syncRequest);
                System.out.println("⇨ Sent SYNC_REQUEST for document: " + documentId);
            }

            @Override
            public void handleTransportError(StompSession session, Throwable exception) {
                System.err.println("❌ Transport error: " + exception.getMessage());
                exception.printStackTrace();
                Platform.runLater(() -> {
                    connectionStatus.set("Connection error: " + exception.getMessage());
                    textArea.setDisable(true);

                    Alert alert = new Alert(Alert.AlertType.ERROR);
                    alert.setTitle("Connection Error");
                    alert.setHeaderText("WebSocket Connection Failed");
                    alert.setContentText("Error: " + exception.getMessage());
                    alert.showAndWait();
                });
            }
        });
    }

    private void handleLocalChange(String oldText, String newText) {
        if (oldText == null || newText == null) return;

        // Find the differences between old and new text
        int[] diffInfo = calculateTextDifference(oldText, newText);
        int position = diffInfo[0];
        int deletedLength = diffInfo[1];
        int insertedLength = diffInfo[2];

        // Handle deletes first if needed
        if (deletedLength > 0) {
            synchronized (pendingDeletePositions) {
                pendingDeletePositions.add(position);
                pendingDeleteLengths.add(deletedLength);

                // Reset timer for batch processing
                if (batchTimer.getStatus() == javafx.animation.Animation.Status.RUNNING) {
                    batchTimer.stop();
                }
                batchTimer.play();
            }
        }

        // Handle inserts if needed
        if (insertedLength > 0) {
            String insertedText = newText.substring(position, position + insertedLength);
            handleLocalInsert(insertedText, position);
        }

        // Save state for undo
        saveStateForUndo(oldText);
    }

    private int[] calculateTextDifference(String oldText, String newText) {
        int commonPrefixLength = 0;
        int minLength = Math.min(oldText.length(), newText.length());

        // Find common prefix
        while (commonPrefixLength < minLength &&
                oldText.charAt(commonPrefixLength) == newText.charAt(commonPrefixLength)) {
            commonPrefixLength++;
        }

        // Find common suffix
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

    private void saveStateForUndo(String previousState) {
        // Don't save state during undo/redo operations
        if (isUndoRedoOperation) return;

        // Save current state to undo stack
        undoStack.push(new UndoRedoState(previousState, new ArrayList<>(characterIds)));

        // Clear redo stack when new edits are made
        redoStack.clear();

        // Limit undo stack size
        if (undoStack.size() > MAX_UNDO_HISTORY) {
            undoStack.removeLast();
        }
    }

    private void performUndo() {
        if (undoStack.isEmpty()) return;

        // Get previous state
        UndoRedoState state = undoStack.pop();

        // Save current state to redo stack
        redoStack.push(new UndoRedoState(textArea.getText(), new ArrayList<>(characterIds)));

        // Apply previous state
        isUndoRedoOperation = true;
        try {
            isProcessingRemoteOperation.set(true);
            textArea.setText(state.text);

            synchronized (documentLock) {
                characterIds.clear();
                characterIds.addAll(state.characterIds);
                previousContent = state.text;
            }
        } finally {
            isProcessingRemoteOperation.set(false);
            isUndoRedoOperation = false;
        }
    }

    private void performRedo() {
        if (redoStack.isEmpty()) return;

        // Get next state
        UndoRedoState state = redoStack.pop();

        // Save current state to undo stack
        undoStack.push(new UndoRedoState(textArea.getText(), new ArrayList<>(characterIds)));

        // Apply next state
        isUndoRedoOperation = true;
        try {
            isProcessingRemoteOperation.set(true);
            textArea.setText(state.text);

            synchronized (documentLock) {
                characterIds.clear();
                characterIds.addAll(state.characterIds);
                previousContent = state.text;
            }
        } finally {
            isProcessingRemoteOperation.set(false);
            isUndoRedoOperation = false;
        }
    }

    private void processPendingEdits() {
        synchronized (pendingDeletePositions) {
            if (pendingDeletePositions.isEmpty()) return;

            // Process all pending deletes as a batch
            for (int i = 0; i < pendingDeletePositions.size(); i++) {
                int position = pendingDeletePositions.get(i);
                int length = pendingDeleteLengths.get(i);
                handleLocalDelete(position, length);
            }

            pendingDeletePositions.clear();
            pendingDeleteLengths.clear();
        }
    }

    private void handleLocalInsert(String text, int position) {
        synchronized (documentLock) {
            try {
                // Validate position
                if (position < 0 || position > characterIds.size()) {
                    System.err.println("Invalid insert position: " + position);
                    return;
                }

                // Generate a unique ID for each character
                List<String> charIds = new ArrayList<>();
                long timestamp = System.currentTimeMillis();
                for (int i = 0; i < text.length(); i++) {
                    charIds.add(clientId + ":" + timestamp + ":" + i);
                }

                // Calculate path for CRDT
                List<String> path = new ArrayList<>();
                if (position == 0) {
                    path.add("start");
                } else if (position >= characterIds.size()) {
                    path.add("end");
                } else {
                    path.add("after-" + characterIds.get(position - 1));
                }

                // Create operation
                Operation operation = new Operation(
                        Operation.Type.INSERT,
                        text,
                        path,
                        timestamp,
                        clientId
                );

                // Add to operation queue for sending
                operationQueue.add(operation);

                // Update local state
                characterIds.addAll(position, charIds);
                previousContent = textArea.getText();

                System.out.println("⇨ Queued INSERT operation: " + text + " at position " + position);
            } catch (Exception e) {
                System.err.println("Error handling insert: " + e.getMessage());
                e.printStackTrace();
            }
        }
    }

    private void handleLocalDelete(int position, int length) {
        synchronized (documentLock) {
            try {
                // Validate position and length
                if (position < 0 || length <= 0) {
                    System.err.println("Invalid delete parameters: position=" + position + ", length=" + length);
                    return;
                }

                // Check if we have enough characters to delete
                if (position >= characterIds.size()) {
                    System.err.println("Delete position beyond character IDs size: " + position + " >= " + characterIds.size());
                    return;
                }

                // Limit the length to avoid going out of bounds
                int endPos = Math.min(position + length, characterIds.size());
                int actualLength = endPos - position;

                if (actualLength <= 0) {
                    System.err.println("No characters to delete after bounds checking");
                    return;
                }

                // Get character IDs for the deleted range
                List<String> deletedCharIds = new ArrayList<>(characterIds.subList(position, endPos));

                // Create paths with char IDs for the CRDT
                List<String> path = new ArrayList<>();
                for (String charId : deletedCharIds) {
                    path.add("char-" + charId);
                }

                // Create content string for the delete operation
                String deletedContent = previousContent.substring(position, Math.min(position + actualLength, previousContent.length()));

                // Create operation
                Operation operation = new Operation(
                        Operation.Type.DELETE,
                        deletedContent,
                        path,
                        System.currentTimeMillis(),
                        clientId
                );

                // Add to operation queue for sending
                operationQueue.add(operation);

                // Update local state
                characterIds.subList(position, endPos).clear();
                previousContent = textArea.getText();

                System.out.println("⇨ Queued DELETE operation at position " + position +
                        ", deleted " + actualLength + " chars");
            } catch (Exception e) {
                System.err.println("Error handling delete: " + e.getMessage());
                e.printStackTrace();
            }
        }
    }

    private void flushOperations() {
        Operation op = operationQueue.poll();
        if (op != null) {
            try {
                EditorMessage message = new EditorMessage();
                message.setType(EditorMessage.MessageType.OPERATION);
                message.setClientId(clientId);
                message.setDocumentId(documentId);
                message.setOperation(op);

                stompSession.send("/app/editor/operation", message);
                System.out.println("⇨ Sent operation: " + op.getType());
            } catch (Exception e) {
                System.err.println("Error sending operation: " + e.getMessage());
                e.printStackTrace();
                // Put the operation back in queue to try again
                operationQueue.add(op);
            }
        }
    }

    private void handleRemoteMessage(EditorMessage message) {
        if (!documentId.equals(message.getDocumentId())) return;

        Platform.runLater(() -> {
            synchronized (documentLock) {
                try {
                    isProcessingRemoteOperation.set(true);

                    // Save caret position
                    int caretPos = textArea.getCaretPosition();

                    if (message.getType() == EditorMessage.MessageType.OPERATION &&
                            !message.getOperation().getClientId().equals(clientId)) {
                        Operation op = message.getOperation();

                        // Save state for undo before applying remote changes
                        saveStateForUndo(textArea.getText());

                        if (op.getType() == Operation.Type.INSERT) {
                            processRemoteInsert(op, caretPos);
                        } else if (op.getType() == Operation.Type.DELETE) {
                            processRemoteDelete(op, caretPos);
                        }

                        // Validate state after operation
                        validateClientState();
                    } else if (message.getType() == EditorMessage.MessageType.SYNC_RESPONSE) {
                        // Save state for undo before syncing
                        if (!textArea.getText().isEmpty()) {
                            saveStateForUndo(textArea.getText());
                        }

                        processSyncResponse(message, caretPos);
                    }

                } catch (Exception e) {
                    System.err.println("Error handling remote message: " + e.getMessage());
                    e.printStackTrace();
                } finally {
                    isProcessingRemoteOperation.set(false);
                }
            }
        });
    }

    private void processRemoteInsert(Operation op, int caretPos) {
        try {
            // Calculate the position to insert
            int pos = calculatePositionFromPath(op.getPath());
            pos = Math.max(0, Math.min(pos, textArea.getText().length()));

            // Create character IDs for inserted content
            List<String> charIds = new ArrayList<>();
            for (int i = 0; i < op.getContent().length(); i++) {
                charIds.add(op.getClientId() + ":" + op.getTimestamp() + ":" + i);
            }

            // Insert the content and update character IDs
            String currentText = textArea.getText();
            String newText = currentText.substring(0, pos) + op.getContent() +
                    currentText.substring(pos);
            textArea.setText(newText);
            characterIds.addAll(pos, charIds);

            // Update caret position if needed
            if (pos <= caretPos) {
                textArea.positionCaret(caretPos + op.getContent().length());
            } else {
                textArea.positionCaret(caretPos);
            }

            // Update state
            previousContent = textArea.getText();
            System.out.println("Applied remote INSERT: " + op.getContent().length() + " chars at position " + pos);
        } catch (Exception e) {
            System.err.println("Error processing remote insert: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void processRemoteDelete(Operation op, int caretPos) {
        try {
            Set<Integer> positionsToDelete = new TreeSet<>(Collections.reverseOrder());

            // Get positions from character IDs in the path
            for (String pathEntry : op.getPath()) {
                if (pathEntry.startsWith("char-")) {
                    String charId = pathEntry.substring(5);
                    int index = characterIds.indexOf(charId);
                    if (index != -1) {
                        positionsToDelete.add(index);
                    }
                }
            }

            if (positionsToDelete.isEmpty()) {
                System.out.println("No valid positions found for remote DELETE operation");
                return;
            }

            // Build a new text by removing characters at specific positions
            StringBuilder sb = new StringBuilder(textArea.getText());
            int caretAdjustment = 0;

            // Delete in reverse order to avoid index shifting issues
            for (int pos : positionsToDelete) {
                if (pos >= 0 && pos < sb.length()) {
                    sb.deleteCharAt(pos);

                    // Remove character ID
                    if (pos < characterIds.size()) {
                        characterIds.remove(pos);
                    }

                    // Adjust caret position if needed
                    if (pos < caretPos) {
                        caretAdjustment--;
                    }
                }
            }

            // Update text area with modified content
            textArea.setText(sb.toString());

            // Set caret position and update state
            int newCaretPos = Math.max(0, Math.min(caretPos + caretAdjustment, textArea.getLength()));
            textArea.positionCaret(newCaretPos);
            previousContent = textArea.getText();

            System.out.println("Applied remote DELETE for " + positionsToDelete.size() + " characters");
        } catch (Exception e) {
            System.err.println("Error processing remote delete: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void processSyncResponse(EditorMessage message, int caretPos) {
        try {
            String content = message.getContent();
            List<String> serverCharIds = message.getCharacterIds();

            if (content != null) {
                // Update text content
                textArea.setText(content);

                // Use server-provided character IDs when available
                if (serverCharIds != null && !serverCharIds.isEmpty()) {
                    System.out.println("Using " + serverCharIds.size() + " character IDs from server");
                    characterIds.clear();
                    characterIds.addAll(serverCharIds);
                } else {
                    // Fall back to generating new IDs if none provided
                    characterIds.clear();
                    for (int i = 0; i < content.length(); i++) {
                        characterIds.add("sync:" + System.currentTimeMillis() + ":" + i);
                    }
                    System.out.println("Server did not provide character IDs, generated " + characterIds.size() + " new IDs");
                }

                // Update state
                previousContent = content;
                System.out.println("Applied SYNC_RESPONSE with content length: " + content.length());

                // Validate that character ID count matches content length
                if (characterIds.size() != content.length()) {
                    System.err.println("WARNING: Character ID count (" + characterIds.size() +
                            ") does not match content length (" + content.length() + ")");
                }
            } else {
                // Empty document
                textArea.setText("");
                characterIds.clear();
                previousContent = "";
                System.out.println("Applied SYNC_RESPONSE with empty content");
            }

            // Adjust caret position
            int newPos = Math.min(caretPos, textArea.getLength());
            textArea.positionCaret(newPos);
        } catch (Exception e) {
            System.err.println("Error processing sync response: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private int calculatePositionFromPath(List<String> path) {
        if (path == null || path.isEmpty()) return 0;

        if (path.contains("start")) return 0;
        if (path.contains("end")) return characterIds.size();

        // First try after-X paths
        for (String pathEntry : path) {
            if (pathEntry.startsWith("after-")) {
                String charId = pathEntry.substring(6);
                int index = characterIds.indexOf(charId);
                if (index != -1) {
                    return index + 1;
                }
            }
        }

        // Then try direct char-X paths
        for (String pathEntry : path) {
            if (pathEntry.startsWith("char-")) {
                String charId = pathEntry.substring(5);
                int index = characterIds.indexOf(charId);
                if (index != -1) {
                    return index;
                }
            }
        }

        return characterIds.size(); // Default to end of document
    }

    private void validateClientState() {
        int textLength = textArea.getText().length();
        int charIdsCount = characterIds.size();

        if (textLength != charIdsCount) {
            System.err.println("WARNING: Client state inconsistency detected!");
            System.err.println("Text length: " + textLength + ", Character IDs count: " + charIdsCount);

            // Request a fresh sync from server to fix the inconsistency
            if (stompSession != null) {
                try {
                    EditorMessage syncRequest = new EditorMessage();
                    syncRequest.setType(EditorMessage.MessageType.SYNC_REQUEST);
                    syncRequest.setClientId(clientId);
                    syncRequest.setDocumentId(documentId);
                    stompSession.send("/app/editor/operation", syncRequest);
                    System.out.println("⇨ Sent SYNC_REQUEST to recover from inconsistent state");
                } catch (Exception e) {
                    System.err.println("Error sending sync request: " + e.getMessage());
                }
            }
        }
    }

    private void cleanup() {
        // Stop timers
        if (operationFlushTimer != null) {
            operationFlushTimer.stop();
        }
        if (batchTimer != null) {
            batchTimer.stop();
        }
        if (consistencyCheckTimer != null) {
            consistencyCheckTimer.stop();
        }

        // Close WebSocket connection
        if (stompSession != null && stompSession.isConnected()) {
            try {
                stompSession.disconnect();
                System.out.println("WebSocket disconnected");
            } catch (Exception e) {
                System.err.println("Error disconnecting: " + e.getMessage());
            }
        }
    }
    private static class UndoRedoState {
        final String text;
        final List<String> characterIds;

        UndoRedoState(String text, List<String> characterIds) {
            this.text = text;
            this.characterIds = characterIds;
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}
