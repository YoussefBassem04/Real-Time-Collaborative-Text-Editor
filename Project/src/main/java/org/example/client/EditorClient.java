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
private static final int MAX_UNDO_HISTORY = 100;
private boolean isApplyingState = false;

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

        // Set up specific key handling for backspace
        setupKeyHandling();

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

        // Timer for batching local edits - make it more dynamic for backspace
        batchTimer = new javafx.animation.Timeline(
                new javafx.animation.KeyFrame(Duration.millis(BATCH_DELAY * 2), // Longer batch delay for backspace
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
        if (oldText == null || newText == null || isUndoRedoOperation) return;

        // Save state for undo before applying changes
        saveStateForUndo();

        // Find the differences between old and new text
        int[] diffInfo = calculateTextDifference(oldText, newText);
        int position = diffInfo[0];
        int deletedLength = diffInfo[1];
        int insertedLength = diffInfo[2];

        // For rapid backspace operations, optimize by capturing larger batches
        if (deletedLength > 0) {
            synchronized (pendingDeletePositions) {
                // If we have a pending operation at this position or adjacent, merge them
                boolean merged = false;
                for (int i = 0; i < pendingDeletePositions.size(); i++) {
                    int existingPos = pendingDeletePositions.get(i);
                    // Check if positions are adjacent (current delete starts where previous ends)
                    if (existingPos == position + deletedLength) {
                        // Update existing position to cover both deletions
                        pendingDeletePositions.set(i, position);
                        pendingDeleteLengths.set(i, pendingDeleteLengths.get(i) + deletedLength);
                        merged = true;
                        break;
                    }
                    // Check if new delete happens right after existing one
                    else if (position == existingPos + pendingDeleteLengths.get(i)) {
                        // Extend existing deletion
                        pendingDeleteLengths.set(i, pendingDeleteLengths.get(i) + deletedLength);
                        merged = true;
                        break;
                    }
                }

                // If not merged with existing operations, add as new
                if (!merged) {
                    pendingDeletePositions.add(position);
                    pendingDeleteLengths.add(deletedLength);
                }

                // Reset timer for batch processing - extend delay for backspace key
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

    private void saveStateForUndo() {
        // Don't save states while applying undo/redo
        if (isUndoRedoOperation || isApplyingState) return;
        
        // Save current state to undo stack
        UndoRedoState state = new UndoRedoState(
            textArea.getText(), 
            new ArrayList<>(characterIds),
            textArea.getCaretPosition()
        );
        undoStack.push(state);
    
        // Clear redo stack when new edits are made
        redoStack.clear();
    
        // Limit undo stack size
        if (undoStack.size() > MAX_UNDO_HISTORY) {
            undoStack.removeLast();
        }
        
        System.out.println("Saved state to undo stack. Stack size: " + undoStack.size());
    }
    /**
 * Calculate the required operations to transform sourceText into targetText
 * @param sourceText The source text
 * @param targetText The target text
 * @return A list of operations that transform source into target
 */
private List<org.example.crdt.Operation> calculateDiffOperations(String sourceText, String targetText) {
    List<org.example.crdt.Operation> operations = new ArrayList<>();
    
    // Find common prefix and suffix first
    int[] diffInfo = calculateTextDifference(sourceText, targetText);
    int prefixLength = diffInfo[0];
    int sourceDeleteLength = diffInfo[1];
    int targetInsertLength = diffInfo[2];
    
    // If there are characters to delete
    if (sourceDeleteLength > 0) {
        List<String> deletePaths = new ArrayList<>();
        for (int i = prefixLength; i < prefixLength + sourceDeleteLength; i++) {
            if (i < characterIds.size()) {
                deletePaths.add("char-" + characterIds.get(i));
            }
        }
        
        if (!deletePaths.isEmpty()) {
            org.example.crdt.Operation deleteOp = new org.example.crdt.Operation(
                org.example.crdt.Operation.Type.DELETE,
                sourceText.substring(prefixLength, prefixLength + sourceDeleteLength),
                deletePaths,
                System.currentTimeMillis(),
                clientId
            );
            operations.add(deleteOp);
        }
    }
    
    // If there are characters to insert
    if (targetInsertLength > 0) {
        List<String> insertPath = new ArrayList<>();
        if (prefixLength == 0) {
            insertPath.add("start");
        } else if (prefixLength >= characterIds.size()) {
            insertPath.add("end");
        } else {
            insertPath.add("after-" + characterIds.get(prefixLength - 1));
        }
        
        org.example.crdt.Operation insertOp = new org.example.crdt.Operation(
            org.example.crdt.Operation.Type.INSERT,
            targetText.substring(prefixLength, prefixLength + targetInsertLength),
            insertPath,
            System.currentTimeMillis(),
            clientId
        );
        operations.add(insertOp);
    }
    
    return operations;
}
    private void setupKeyHandling() {
        // Track if backspace is being held down
        final AtomicBoolean isBackspaceHeld = new AtomicBoolean(false);

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

            // Track backspace key state
            if (event.getCode() == javafx.scene.input.KeyCode.BACK_SPACE) {
                isBackspaceHeld.set(true);
                // For held backspace, we should flush operations more frequently
                if (operationFlushTimer != null) {
                    operationFlushTimer.stop();
                    operationFlushTimer.setCycleCount(javafx.animation.Animation.INDEFINITE);
                    operationFlushTimer.setRate(2.0); // Faster flush rate during backspace
                    operationFlushTimer.play();
                }
            }
        });

        textArea.setOnKeyReleased(event -> {
            if (event.getCode() == javafx.scene.input.KeyCode.BACK_SPACE) {
                isBackspaceHeld.set(false);
                // Return to normal operation flush rate
                if (operationFlushTimer != null) {
                    operationFlushTimer.stop();
                    operationFlushTimer.setCycleCount(javafx.animation.Animation.INDEFINITE);
                    operationFlushTimer.setRate(1.0);
                    operationFlushTimer.play();
                }

                // Force process any pending edits immediately when backspace is released
                processPendingEdits();
            }
        });
    }

/**
 * Sends an operation to the server
 * @param operation The operation to send
 */
private void sendOperation(Operation op) {
    if (stompSession == null || !stompSession.isConnected()) {
        System.err.println("Cannot send operation: not connected");
        return;
    }

    try {
        EditorMessage message = new EditorMessage();
        message.setType(EditorMessage.MessageType.OPERATION);
        message.setClientId(clientId);
        message.setDocumentId(documentId);
        message.setOperation(op);

        stompSession.send("/app/editor/operation", message);
        System.out.println("⇨ Sent " + op.getType() + " operation to server");
    } catch (Exception e) {
        System.err.println("Error sending operation: " + e.getMessage());
        e.printStackTrace();
    }
}
/**
 * Generate unique character IDs for newly inserted content
 * @param count Number of characters to generate IDs for
 * @return List of unique character IDs
 */
private List<String> generateCharIds(int count) {
    List<String> charIds = new ArrayList<>();
    long timestamp = System.currentTimeMillis();
    for (int i = 0; i < count; i++) {
        charIds.add(clientId + ":" + timestamp + ":" + i);
    }
    return charIds;
}
    /**
 * Applies an undo/redo state by calculating and applying the differences
 * @param state The target state to apply
 */
private void applyUndoRedoState(UndoRedoState state) {
    synchronized (documentLock) {
        String currentText = textArea.getText();
        String targetText = state.text;

        // Use a diff algorithm to find the minimum operations needed
        List<org.example.crdt.Operation> operations = calculateDiffOperations(currentText, targetText);
        
        // Apply operations to reach the target state
        for (org.example.crdt.Operation op : operations) {
            // Send operations to server
            sendOperation(op);
            
            // Apply operations locally
            if (op.getType() == org.example.crdt.Operation.Type.INSERT) {
                // Apply insert locally
                String path = op.getPath().get(0);
                int position;
                if (path.equals("start")) {
                    position = 0;
                } else if (path.equals("end")) {
                    position = currentText.length();
                } else {
                    // Handle "after-X" path
                    String charId = path.substring(6);
                    position = characterIds.indexOf(charId) + 1;
                }
                
                // Update the text
                currentText = currentText.substring(0, position) + 
                              op.getContent() + 
                              currentText.substring(position);
                
                // Update characterIds
                List<String> newCharIds = generateCharIds(op.getContent().length());
                characterIds.addAll(position, newCharIds);
                
            } else if (op.getType() == org.example.crdt.Operation.Type.DELETE) {
                // Apply delete locally
                for (String path : op.getPath()) {
                    if (path.startsWith("char-")) {
                        String charId = path.substring(5);
                        int index = characterIds.indexOf(charId);
                        if (index != -1 && index < currentText.length()) {
                            // Remove from character IDs
                            characterIds.remove(index);
                            
                            // Remove from text
                            currentText = currentText.substring(0, index) + 
                                         currentText.substring(index + 1);
                        }
                    }
                }
            }
        }
        
        // Update the UI
        textArea.setText(targetText);
        
        // Restore character IDs if they don't match
        if (characterIds.size() != targetText.length()) {
            System.out.println("⚠️ Character IDs mismatch after undo/redo, restoring from saved state");
            characterIds.clear();
            characterIds.addAll(state.characterIds);
        }
        
        // Set caret position if specified
        if (state.caretPosition >= 0 && state.caretPosition <= textArea.getLength()) {
            textArea.positionCaret(state.caretPosition);
        }
        
        // Update state
        previousContent = targetText;
    }
}
    private void executeAndSendDelete(int position, int length) {
        if (position < 0 || length <= 0 || position + length > characterIds.size()) {
            System.err.println("Invalid delete parameters: pos=" + position + ", len=" + length);
            return;
        }

        // Get IDs of characters to delete
        List<String> idsToDelete = new ArrayList<>();
        for (int i = position; i < position + length; i++) {
            if (i < characterIds.size()) {
                idsToDelete.add(characterIds.get(i));
            }
        }

        if (idsToDelete.isEmpty()) {
            System.err.println("No valid IDs to delete");
            return;
        }

        // Create paths for the delete operation
        List<String> paths = new ArrayList<>();
        for (String id : idsToDelete) {
            paths.add("char-" + id);
        }

        // Create and send the operation
        Operation deleteOp = new Operation(
                Operation.Type.DELETE,
                textArea.getText().substring(position, position + length),
                paths,
                System.currentTimeMillis(),
                clientId
        );

        // Execute locally
        synchronized (documentLock) {
            // Update text
            String text = textArea.getText();
            String newText = text.substring(0, position) + text.substring(position + length);
            textArea.setText(newText);

            // Update character IDs
            for (int i = 0; i < length; i++) {
                if (position < characterIds.size()) {
                    characterIds.remove(position);
                }
            }
        }

        // Send to server
        sendOperation(deleteOp);
    }
    private void executeAndSendInsert(String text, int position) {
        if (position < 0 || position > characterIds.size()) {
            System.err.println("Invalid insert position: " + position);
            return;
        }

        // Generate IDs for new characters
        List<String> newIds = new ArrayList<>();
        long timestamp = System.currentTimeMillis();
        for (int i = 0; i < text.length(); i++) {
            newIds.add(clientId + ":" + timestamp + ":" + i);
        }

        // Create path for the insert operation
        List<String> path = new ArrayList<>();
        if (position == 0) {
            path.add("start");
        } else if (position >= characterIds.size()) {
            path.add("end");
        } else {
            path.add("after-" + characterIds.get(position - 1));
        }

        // Create operation
        Operation insertOp = new Operation(
                Operation.Type.INSERT,
                text,
                path,
                timestamp,
                clientId
        );

        // Execute locally
        synchronized (documentLock) {
            // Update text
            String currentText = textArea.getText();
            String newText = currentText.substring(0, position) + text +
                    currentText.substring(position);
            textArea.setText(newText);

            // Update character IDs
            characterIds.addAll(position, newIds);
        }

        // Send to server
        sendOperation(insertOp);
    }

    private List<Operation> calculateTextOperations(String source, String target) {
        List<Operation> operations = new ArrayList<>();
    
        // Simple diff algorithm to find common prefix and suffix
        int prefixLength = 0;
        int minLength = Math.min(source.length(), target.length());
    
        // Find common prefix
        while (prefixLength < minLength &&
                source.charAt(prefixLength) == target.charAt(prefixLength)) {
            prefixLength++;
        }
    
        // Find common suffix
        int sourceEnd = source.length() - 1;
        int targetEnd = target.length() - 1;
        int suffixLength = 0;
    
        while (sourceEnd >= prefixLength && targetEnd >= prefixLength &&
                source.charAt(sourceEnd) == target.charAt(targetEnd)) {
            sourceEnd--;
            targetEnd--;
            suffixLength++;
        }
    
        // Calculate middle sections that differ
        int deleteLength = source.length() - prefixLength - suffixLength;
        int insertLength = target.length() - prefixLength - suffixLength;
    
        // Add delete operation if needed
        if (deleteLength > 0) {
            operations.add(new Operation(prefixLength, deleteLength));
        }
    
        // Add insert operation if needed
        if (insertLength > 0) {
            String insertText = target.substring(prefixLength, prefixLength + insertLength);
            operations.add(new Operation(insertText, prefixLength));
        }
    
        return operations;
    }
    
    private void sendFullStateToServer() {
        if (stompSession == null || !stompSession.isConnected()) {
            System.err.println("Cannot send full state: not connected");
            return;
        }

        try {
            EditorMessage message = new EditorMessage();
            message.setType(EditorMessage.MessageType.FULL_STATE);
            message.setClientId(clientId);
            message.setDocumentId(documentId);
            message.setContent(textArea.getText());
            message.setCharacterIds(new ArrayList<>(characterIds));
            message.setTimestamp(System.currentTimeMillis());

            stompSession.send("/app/editor/full-state", message);
            System.out.println("⇨ Sent full state to server for consistency");
        } catch (Exception e) {
            System.err.println("Error sending full state: " + e.getMessage());
            e.printStackTrace();
        }
    }
    private void syncToState(UndoRedoState state) {
        synchronized (documentLock) {
            try {
                isApplyingState = true;
                
                // Get current text
                String currentText = textArea.getText();
                String targetText = state.text;
                
                if (currentText.equals(targetText)) {
                    System.out.println("No text change needed for undo/redo");
                    return;
                }
                
                // Calculate the difference between current and target
                List<Operation> editOps = calculateTextOperations(currentText, targetText);
                System.out.println("Calculated " + editOps.size() + " edit operations for undo/redo");
                
                // Apply operations in sequence to maintain consistency
                for (Operation editOp : editOps) {
                    if (editOp.type == Operation.Type.DELETE) {
                        // Handle delete operation
                        executeAndSendDelete(editOp.position, editOp.length);
                    } else if (editOp.type == Operation.Type.INSERT) {
                        // Handle insert operation
                        executeAndSendInsert(editOp.content, editOp.position);
                    }
                }
                
                // Final verification
                if (!textArea.getText().equals(targetText)) {
                    System.err.println("WARNING: Text mismatch after undo/redo operations");
                    System.err.println("Expected: " + targetText.length() + " chars");
                    System.err.println("Actual: " + textArea.getText().length() + " chars");
                    
                    // Force sync for consistency
                    textArea.setText(targetText);
                    
                    // Restore character IDs as well
                    characterIds.clear();
                    characterIds.addAll(state.characterIds);
                    
                    // Send full state to server for consistency
                    sendFullStateToServer();
                }
                
                // Set caret position
                if (state.caretPosition >= 0 && state.caretPosition <= textArea.getLength()) {
                    textArea.positionCaret(state.caretPosition);
                }
                
                previousContent = textArea.getText();
                System.out.println("Applied undo/redo state successfully");
            } finally {
                isApplyingState = false;
            }
        }
    }
private void performUndo() {
    if (undoStack.isEmpty()) {
        System.out.println("Nothing to undo");
        return;
    }

    try {
        // Set flag to prevent recursive undo/redo
        isUndoRedoOperation = true;

        // Get previous state
        UndoRedoState prevState = undoStack.pop();

        // Save current state to redo stack first
        redoStack.push(new UndoRedoState(
            textArea.getText(), 
            new ArrayList<>(characterIds),
            textArea.getCaretPosition()
        ));
        
        System.out.println("Performing undo. New redo stack size: " + redoStack.size());

        // Instead of complex diffing, use sync-based approach for consistency
        syncToState(prevState);
    } catch (Exception e) {
        System.err.println("Error performing undo: " + e.getMessage());
        e.printStackTrace();
        // Request sync from server to recover
        requestSync();
    } finally {
        isUndoRedoOperation = false;
    }
}

private void performRedo() {
    if (redoStack.isEmpty()) {
        System.out.println("Nothing to redo");
        return;
    }

    try {
        // Set flag to prevent recursive undo/redo
        isUndoRedoOperation = true;

        // Get the state to redo
        UndoRedoState redoState = redoStack.pop();

        // Save current state to undo stack first
        undoStack.push(new UndoRedoState(
            textArea.getText(), 
            new ArrayList<>(characterIds),
            textArea.getCaretPosition()
        ));
        
        System.out.println("Performing redo. New undo stack size: " + undoStack.size());

        // Use sync-based approach for consistency
        syncToState(redoState);
    } catch (Exception e) {
        System.err.println("Error performing redo: " + e.getMessage());
        e.printStackTrace();
        // Request sync from server to recover
        requestSync();
    } finally {
        isUndoRedoOperation = false;
    }
}


    private void processPendingEdits() {
        synchronized (pendingDeletePositions) {
            if (pendingDeletePositions.isEmpty()) return;

            // Process pending deletes as a single batch operation where possible
            // Sort by position in reverse order to handle deletions from right to left
            Map<Integer, Integer> mergedDeletes = new TreeMap<>(Collections.reverseOrder());

            // Combine consecutive delete operations at the same position
            for (int i = 0; i < pendingDeletePositions.size(); i++) {
                int position = pendingDeletePositions.get(i);
                int length = pendingDeleteLengths.get(i);

                // Merge with existing delete at same position
                mergedDeletes.put(position, mergedDeletes.getOrDefault(position, 0) + length);
            }

            // Process each merged delete operation
            for (Map.Entry<Integer, Integer> entry : mergedDeletes.entrySet()) {
                int position = entry.getKey();
                int length = entry.getValue();
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

                // Get character IDs for the deleted range - make a defensive copy
                List<String> deletedCharIds = new ArrayList<>();
                for (int i = position; i < endPos; i++) {
                    if (i < characterIds.size()) {
                        deletedCharIds.add(characterIds.get(i));
                    }
                }

                if (deletedCharIds.isEmpty()) {
                    System.err.println("No valid character IDs to delete");
                    return;
                }

                // Create paths with char IDs for the CRDT
                List<String> path = new ArrayList<>();
                for (String charId : deletedCharIds) {
                    path.add("char-" + charId);
                }

                // Create content string for the delete operation
                String deletedContent;
                try {
                    deletedContent = previousContent.substring(position, Math.min(position + actualLength, previousContent.length()));
                } catch (StringIndexOutOfBoundsException e) {
                    System.err.println("String index out of bounds: position=" + position + ", length=" + actualLength +
                            ", previousContent.length()=" + previousContent.length());
                    deletedContent = ""; // Fallback to empty string
                }

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

                // Update local state - use subList clear to efficiently remove a range
                if (endPos <= characterIds.size()) {
                    characterIds.subList(position, endPos).clear();
                }
                previousContent = textArea.getText();

                System.out.println("⇨ Queued DELETE operation at position " + position +
                        ", deleted " + actualLength + " chars");
            } catch (Exception e) {
                System.err.println("Error handling delete: " + e.getMessage());
                e.printStackTrace();

                // Request sync if we encounter errors during rapid deletes
                requestSync();
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
    private void requestSync() {
        if (stompSession != null && stompSession.isConnected()) {
            try {
                EditorMessage syncRequest = new EditorMessage();
                syncRequest.setType(EditorMessage.MessageType.SYNC_REQUEST);
                syncRequest.setClientId(clientId);
                syncRequest.setDocumentId(documentId);
                stompSession.send("/app/editor/operation", syncRequest);
                System.out.println("⇨ Sent SYNC_REQUEST");
            } catch (Exception e) {
                System.err.println("Error sending sync request: " + e.getMessage());
            }
        }
    }

    /**
 * Handles incoming messages from the server
 * @param message The incoming editor message
 */
    private void handleRemoteMessage(EditorMessage message) {
        // Ignore messages for other documents
        if (!documentId.equals(message.getDocumentId())) return;

        Platform.runLater(() -> {
            synchronized (documentLock) {
                try {
                    isProcessingRemoteOperation.set(true);

                    // Save caret position
                    int caretPos = textArea.getCaretPosition();

                    if (message.getType() == EditorMessage.MessageType.OPERATION) {
                        Operation op = message.getOperation();

                        // Only process operations from other clients
                        if (!op.getClientId().equals(clientId)) {
                            // Save current state for undo
                            if (!isUndoRedoOperation && !isApplyingState) {
                                saveStateForUndo();
                            }

                            if (op.getType() == Operation.Type.INSERT) {
                                processRemoteInsert(op, caretPos);
                            } else if (op.getType() == Operation.Type.DELETE) {
                                processRemoteDelete(op, caretPos);
                            }
                        }

                        // Validate state after operation
                        validateClientState();
                    } else if (message.getType() == EditorMessage.MessageType.SYNC_RESPONSE) {
                        // Save state for undo before syncing
                        if (!textArea.getText().isEmpty() && !isUndoRedoOperation && !isApplyingState) {
                            saveStateForUndo();
                        }

                        processSyncResponse(message, caretPos);
                    } else if (message.getType() == EditorMessage.MessageType.FULL_STATE) {
                        // Process full state message (if your server implements this)
                        processSyncResponse(message, caretPos);
                    }

                } catch (Exception e) {
                    System.err.println("Error handling remote message: " + e.getMessage());
                    e.printStackTrace();

                    // Request a sync on error
                    requestSync();
                } finally {
                    isProcessingRemoteOperation.set(false);
                }
            }
        });
    }

    /**
 * Processes a remote insert operation
 * @param op The insert operation
 * @param caretPos Current caret position
 */
private void processRemoteInsert(Operation op, int caretPos) {
    try {
        // Calculate the position to insert based on path
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

        // Update caret position intelligently
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

    /**
 * Processes a remote delete operation
 * @param op The delete operation
 * @param caretPos Current caret position
 */
private void processRemoteDelete(Operation op, int caretPos) {
    try {
        // Collect all positions to delete, in reverse order
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

                // Adjust caret position if deletion is before it
                if (pos < caretPos) {
                    caretAdjustment--;
                }
            }
        }

        // Update text area with modified content
        textArea.setText(sb.toString());

        // Set adjusted caret position
        int newCaretPos = Math.max(0, Math.min(caretPos + caretAdjustment, textArea.getLength()));
        textArea.positionCaret(newCaretPos);
        previousContent = textArea.getText();

        System.out.println("Applied remote DELETE for " + positionsToDelete.size() + " characters");
    } catch (Exception e) {
        System.err.println("Error processing remote delete: " + e.getMessage());
        e.printStackTrace();
    }
}

    /**
 * Processes a sync response from the server
 * @param message The sync response message
 * @param caretPos Current caret position
 */
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
            // Handle empty document
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
        if (!isUndoRedoOperation && !isApplyingState) {
            int textLength = textArea.getText().length();
            int charIdsCount = characterIds.size();

            if (textLength != charIdsCount) {
                System.err.println("WARNING: Client state inconsistency detected!");
                System.err.println("Text length: " + textLength + ", Character IDs count: " + charIdsCount);

                // Request a sync to fix the inconsistency
                requestSync();
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
    
/**
 * Enhanced UndoRedoState class to track all necessary state information
 */
private static class UndoRedoState {
    final String text;
    final List<String> characterIds;
    final int caretPosition;
    final long timestamp;

    UndoRedoState(String text, List<String> characterIds, int caretPosition) {
        this.text = text;
        this.characterIds = new ArrayList<>(characterIds);
        this.caretPosition = caretPosition;
        this.timestamp = System.currentTimeMillis();
    }
}

    public static void main(String[] args) {
        launch(args);
    }
}
