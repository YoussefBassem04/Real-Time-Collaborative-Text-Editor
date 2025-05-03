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
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.BadLocationException;
import java.awt.*;
import java.lang.reflect.Type;
import java.util.*;
import java.util.List;
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
    // Store character IDs for CRDT (e.g., ["client1:1234567890", "client1:1234567891", ...])
    private List<String> characterIds = new ArrayList<>();

    public EditorClient() {
        initializeClientInfo();
        initializeUI();
        connectToWebSocket();
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
                    handleLocalDelete(offset, length);
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

    private void handleLocalInsert(String text, int position) {
        synchronized (documentLock) {
            try {
                // Generate a unique ID for each character
                List<String> charIds = new ArrayList<>();
                for (int i = 0; i < text.length(); i++) {
                    charIds.add(clientId + ":" + System.currentTimeMillis() + ":" + i);
                }

                // Calculate path using character IDs
                List<String> path = calculatePathForPosition(position);

                EditorMessage message = new EditorMessage();
                message.setType(EditorMessage.MessageType.OPERATION);
                message.setClientId(clientId);
                message.setDocumentId(documentId);
                message.setOperation(new Operation(Operation.Type.INSERT, text, path, System.currentTimeMillis(), clientId));

                // Update local state
                characterIds.addAll(position, charIds);
                previousContent = textArea.getText();

                System.out.println("⇨ Sending INSERT operation: " + text + " at position " + position +
                        ", Path: " + path + ", Char IDs: " + charIds);
                stompSession.send("/app/editor/operation", message);
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
                if (position < 0 || length <= 0 || position >= characterIds.size()) {
                    System.err.println("Invalid delete parameters: position=" + position + ", length=" + length);
                    return;
                }
    
                // Get the character IDs for the deleted range
                int endPos = Math.min(position + length, characterIds.size());
                List<String> deletedCharIds = new ArrayList<>(characterIds.subList(position, endPos));
    
                // Get the deleted content for reference
                String deletedContent = "";
                try {
                    deletedContent = textArea.getText(position, length);
                } catch (BadLocationException e) {
                    System.err.println("Error retrieving deleted content: " + e.getMessage());
                }
    
                // Construct path with character IDs
                List<String> path = new ArrayList<>();
                for (String charId : deletedCharIds) {
                    path.add("char-" + charId);
                }
    
                // Create and send the delete operation
                EditorMessage message = new EditorMessage();
                message.setType(EditorMessage.MessageType.OPERATION);
                message.setClientId(clientId);
                message.setDocumentId(documentId);
                message.setOperation(new Operation(Operation.Type.DELETE, deletedContent, path, System.currentTimeMillis(), clientId));
    
                // Update local state
                characterIds.subList(position, endPos).clear();
                previousContent = textArea.getText();
    
                System.out.println("⇨ Sending DELETE operation at position " + position +
                        ", deleted: '" + deletedContent + "', Char IDs: " + deletedCharIds);
                stompSession.send("/app/editor/operation", message);
            } catch (Exception e) {
                System.err.println("Error handling delete: " + e.getMessage());
                e.printStackTrace();
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
                        Operation transformedOp = transformOperation(op);
    
                        if (transformedOp.getType() == Operation.Type.INSERT) {
                            int pos = calculatePositionFromPath(transformedOp.getPath());
                            List<String> charIds = new ArrayList<>();
                            for (int i = 0; i < transformedOp.getContent().length(); i++) {
                                charIds.add(transformedOp.getClientId() + ":" + transformedOp.getTimestamp() + ":" + i);
                            }
                            textArea.insert(transformedOp.getContent(), pos);
                            characterIds.addAll(pos, charIds);
                            if (pos <= caretPos) {
                                textArea.setCaretPosition(caretPos + transformedOp.getContent().length());
                            }
                        } else if (transformedOp.getType() == Operation.Type.DELETE) {
                            List<String> path = transformedOp.getPath();
                            List<Integer> positions = new ArrayList<>();
                            // Collect positions of characters to delete
                            for (String pathEntry : path) {
                                if (pathEntry.startsWith("char-")) {
                                    String charId = pathEntry.substring(5);
                                    int index = characterIds.indexOf(charId);
                                    if (index != -1) {
                                        positions.add(index);
                                    }
                                }
                            }
                            // Sort positions in descending order to delete from end to start
                            positions.sort(Collections.reverseOrder());
                            for (int pos : positions) {
                                textArea.replaceRange("", pos, pos + 1);
                                characterIds.remove(pos);
                                if (pos < caretPos) {
                                    caretPos--;
                                }
                            }
                            textArea.setCaretPosition(caretPos);
                        }
                        previousContent = textArea.getText();
                    } else if (message.getType() == EditorMessage.MessageType.SYNC_RESPONSE) {
                        String content = message.getContent();
                        if (content != null && !content.equals(textArea.getText())) {
                            textArea.setText(content);
                            previousContent = content;
                            characterIds.clear();
                            for (int i = 0; i < content.length(); i++) {
                                characterIds.add("sync:" + i);
                            }
                        } else if (content == null) {
                            textArea.setText("");
                            previousContent = "";
                            characterIds.clear();
                        }
                        textArea.setCaretPosition(Math.min(caretPos, textArea.getDocument().getLength()));
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

    private List<String> calculatePathForPosition(int position) {
        List<String> path = new ArrayList<>();
        synchronized (documentLock) {
            if (position == 0) {
                path.add("start");
            } else if (position >= characterIds.size()) {
                path.add("end");
            } else {
                path.add("after-" + characterIds.get(position - 1));
            }
        }
        return path;
    }

    private int calculatePositionFromPath(List<String> path) {
        synchronized (documentLock) {
            if (path == null || path.isEmpty()) return 0;
            if (path.contains("start")) return 0;
            if (path.contains("end")) return characterIds.size();
    
            for (String pathEntry : path) {
                if (pathEntry.startsWith("char-")) {
                    String charId = pathEntry.substring(5);
                    int index = characterIds.indexOf(charId);
                    if (index != -1) {
                        return index;
                    }
                } else if (pathEntry.startsWith("after-")) {
                    String charId = pathEntry.substring(6);
                    int index = characterIds.indexOf(charId);
                    if (index != -1) {
                        return index + 1;
                    }
                }
            }
            // Fallback to end of document
            return characterIds.size();
        }
    }

    private Operation transformOperation(Operation op) {
        synchronized (documentLock) {
            List<String> path = op.getPath();
            int pos = calculatePositionFromPath(path);
    
            if (op.getType() == Operation.Type.INSERT) {
                for (String id : characterIds.subList(0, Math.min(pos, characterIds.size()))) {
                    String[] parts = id.split(":");
                    if (parts.length > 1 && parts[0].compareTo(op.getClientId()) < 0 &&
                            op.getTimestamp() - Long.parseLong(parts[1]) < 1000) {
                        List<String> newPath = calculatePathForPosition(pos + 1);
                        return new Operation(op.getType(), op.getContent(), newPath,
                                op.getTimestamp(), op.getClientId());
                    }
                }
            } else if (op.getType() == Operation.Type.DELETE) {
                // For deletions, check if any recent inserts occurred before the deletion range
                int shift = 0;
                for (String id : characterIds.subList(0, Math.min(pos, characterIds.size()))) {
                    String[] parts = id.split(":");
                    if (parts.length > 1 && parts[0].compareTo(op.getClientId()) < 0 &&
                            op.getTimestamp() - Long.parseLong(parts[1]) < 1000) {
                        shift++;
                    }
                }
                if (shift > 0) {
                    List<String> newPath = new ArrayList<>();
                    for (String pathEntry : path) {
                        if (pathEntry.startsWith("char-")) {
                            String charId = pathEntry.substring(5);
                            int index = characterIds.indexOf(charId);
                            if (index != -1) {
                                // Recalculate path for shifted position
                                newPath.add("char-" + characterIds.get(Math.min(index + shift, characterIds.size() - 1)));
                            } else {
                                newPath.add(pathEntry);
                            }
                        } else {
                            newPath.add(pathEntry);
                        }
                    }
                    return new Operation(op.getType(), op.getContent(), newPath,
                            op.getTimestamp(), op.getClientId());
                }
            }
            return op;
        }
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(EditorClient::new);
    }
}