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

import javax.swing.*;
import javax.swing.Timer;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.BadLocationException;
import java.awt.*;
import java.lang.reflect.Type;
import java.util.*;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

public class EditorClient {
    private JFrame frame;
    private JTextArea textArea;
    private StompSession stompSession;
    private String clientId;
    private String documentId;
    private AtomicBoolean isProcessingRemoteOperation = new AtomicBoolean(false);
    private final Object documentLock = new Object();
    private String previousContent = "";

    // Store character IDs for CRDT
    private List<String> characterIds = new ArrayList<>();

    // Queue for batching operations
    private Queue<Operation> operationQueue = new ConcurrentLinkedQueue<>();

    // Rate limiting for operations
    private Timer operationFlushTimer;
    private static final int OPERATION_FLUSH_DELAY = 100; // ms

    // Batching for multiple delete/insert operations
    private Timer batchTimer;
    private List<Integer> pendingDeletePositions = new ArrayList<>();
    private List<Integer> pendingDeleteLengths = new ArrayList<>();
    private static final int BATCH_DELAY = 50; // ms

    public EditorClient() {
        initializeClientInfo();
        initializeUI();
        initializeTimers();
        connectToWebSocket();
    }

    private void initializeTimers() {
        // Timer for flushing operations to server
        operationFlushTimer = new Timer(OPERATION_FLUSH_DELAY, e -> flushOperations());
        operationFlushTimer.setRepeats(true);
        operationFlushTimer.start();

        // Timer for batching local edits
        batchTimer = new Timer(BATCH_DELAY, e -> processPendingEdits());
        batchTimer.setRepeats(false);
        Timer consistencyCheckTimer = new Timer(5000, e -> validateClientState());
        consistencyCheckTimer.setRepeats(true);
        consistencyCheckTimer.start();
    }

    private void initializeClientInfo() {
        JPanel panel = new JPanel(new GridLayout(0, 1));
        JTextField docIdField = new JTextField(10);
        docIdField.setText("default");
        panel.add(new JLabel("Enter document ID to join:"));
        panel.add(docIdField);

        JRadioButton newIdButton = new JRadioButton("Generate new client ID");
        JRadioButton existingIdButton = new JRadioButton("Use existing client ID");
        ButtonGroup group = new ButtonGroup();
        group.add(newIdButton);
        group.add(existingIdButton);
        newIdButton.setSelected(true);

        panel.add(newIdButton);
        panel.add(existingIdButton);

        JTextField clientIdField = new JTextField(20);
        clientIdField.setEnabled(false);
        panel.add(new JLabel("Enter existing client ID:"));
        panel.add(clientIdField);

        newIdButton.addActionListener(e -> clientIdField.setEnabled(false));
        existingIdButton.addActionListener(e -> clientIdField.setEnabled(true));

        int result = JOptionPane.showConfirmDialog(null, panel,
                "Collaborative Editor Settings", JOptionPane.OK_CANCEL_OPTION);

        if (result == JOptionPane.CANCEL_OPTION) {
            System.exit(0);
        }

        this.documentId = docIdField.getText().trim();
        if (this.documentId.isEmpty()) {
            this.documentId = "default";
        }

        if (newIdButton.isSelected()) {
            this.clientId = UUID.randomUUID().toString();
        } else {
            String inputId = clientIdField.getText().trim();
            this.clientId = inputId.isEmpty() ? UUID.randomUUID().toString() : inputId;
        }

        System.out.println("Initializing with Client ID: " + this.clientId);
        System.out.println("Joining Document ID: " + this.documentId);
    }

    private void initializeUI() {
        frame = new JFrame("Collaborative Editor - Doc: " + documentId + " - Client: " + clientId.substring(0, 8));
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setSize(800, 600);

        textArea = new JTextArea();
        textArea.setFont(new Font("Monospaced", Font.PLAIN, 14));
        textArea.setLineWrap(true);
        textArea.setWrapStyleWord(true);

        textArea.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                if (!isProcessingRemoteOperation.get()) {
                    try {
                        int offset = e.getOffset();
                        int length = e.getLength();
                        String insertedText = textArea.getText(offset, length);
                        handleLocalInsert(insertedText, offset);
                    } catch (BadLocationException ex) {
                        ex.printStackTrace();
                    }
                }
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                if (!isProcessingRemoteOperation.get()) {
                    int offset = e.getOffset();
                    int length = e.getLength();

                    // Add to batch for processing
                    synchronized (pendingDeletePositions) {
                        pendingDeletePositions.add(offset);
                        pendingDeleteLengths.add(length);

                        // Reset timer for batch processing
                        if (batchTimer.isRunning()) {
                            batchTimer.restart();
                        } else {
                            batchTimer.start();
                        }
                    }
                }
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                // Not used for plain text
            }
        });

        JScrollPane scrollPane = new JScrollPane(textArea);
        JPanel statusPanel = new JPanel(new BorderLayout());
        JLabel statusLabel = new JLabel(" Connected as: " + clientId.substring(0, 8) + " | Document: " + documentId);
        statusPanel.add(statusLabel, BorderLayout.WEST);

        frame.add(scrollPane, BorderLayout.CENTER);
        frame.add(statusPanel, BorderLayout.SOUTH);
        frame.setVisible(true);
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
                SwingUtilities.invokeLater(() -> {
                    JOptionPane.showMessageDialog(frame,
                            "Connection error: " + exception.getMessage(),
                            "Connection Error",
                            JOptionPane.ERROR_MESSAGE);
                });
            }
        });
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

        SwingUtilities.invokeLater(() -> {
            synchronized (documentLock) {
                try {
                    isProcessingRemoteOperation.set(true);
                    int caretPos = textArea.getCaretPosition();

                    if (message.getType() == EditorMessage.MessageType.OPERATION &&
                            !message.getOperation().getClientId().equals(clientId)) {
                        Operation op = message.getOperation();

                        if (op.getType() == Operation.Type.INSERT) {
                            processRemoteInsert(op, caretPos);
                        } else if (op.getType() == Operation.Type.DELETE) {
                            processRemoteDelete(op, caretPos);
                        }

                        // Validate state after operation
                        validateClientState();
                    } else if (message.getType() == EditorMessage.MessageType.SYNC_RESPONSE) {
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
            textArea.insert(op.getContent(), pos);
            characterIds.addAll(pos, charIds);

            // Update caret position if needed
            if (pos <= caretPos) {
                textArea.setCaretPosition(caretPos + op.getContent().length());
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

            // Delete in reverse order to avoid index shifting issues
            for (int pos : positionsToDelete) {
                if (pos >= 0 && pos < textArea.getText().length()) {
                    textArea.replaceRange("", pos, pos + 1);

                    // Remove character ID
                    if (pos < characterIds.size()) {
                        characterIds.remove(pos);
                    }

                    // Adjust caret position if needed
                    if (pos < caretPos) {
                        caretPos--;
                    }
                }
            }

            // Set caret position and update state
            textArea.setCaretPosition(Math.max(0, Math.min(caretPos, textArea.getText().length())));
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
            textArea.setCaretPosition(Math.min(caretPos, textArea.getDocument().getLength()));
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

    public static void main(String[] args) {
        SwingUtilities.invokeLater(EditorClient::new);
    }
}