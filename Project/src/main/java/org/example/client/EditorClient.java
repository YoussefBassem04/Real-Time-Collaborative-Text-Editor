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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

public class EditorClient {
    private JFrame frame;
    private JTextArea textArea;
    private StompSession stompSession;
    private String clientId;
    private String documentId;
    private AtomicBoolean isProcessingRemoteOperation = new AtomicBoolean(false);
    private final Object documentLock = new Object();

    public EditorClient() {
        // Get client ID and document ID from user
        initializeClientInfo();
        initializeUI();
        connectToWebSocket();
    }

    private void initializeClientInfo() {
        // Create a panel for user input
        JPanel panel = new JPanel(new GridLayout(0, 1));

        // Create input field for document ID
        JTextField docIdField = new JTextField(10);
        docIdField.setText("default"); // Default document ID
        panel.add(new JLabel("Enter document ID to join:"));
        panel.add(docIdField);

        // Create options for client ID: generate new or enter existing
        JRadioButton newIdButton = new JRadioButton("Generate new client ID");
        JRadioButton existingIdButton = new JRadioButton("Use existing client ID");
        ButtonGroup group = new ButtonGroup();
        group.add(newIdButton);
        group.add(existingIdButton);
        newIdButton.setSelected(true);

        panel.add(newIdButton);
        panel.add(existingIdButton);

        // Create field for entering existing client ID
        JTextField clientIdField = new JTextField(20);
        clientIdField.setEnabled(false);
        panel.add(new JLabel("Enter existing client ID:"));
        panel.add(clientIdField);

        // Enable/disable client ID field based on radio button selection
        newIdButton.addActionListener(e -> clientIdField.setEnabled(false));
        existingIdButton.addActionListener(e -> clientIdField.setEnabled(true));

        // Show dialog to user
        int result = JOptionPane.showConfirmDialog(null, panel,
                "Collaborative Editor Settings", JOptionPane.OK_CANCEL_OPTION);

        if (result == JOptionPane.CANCEL_OPTION) {
            System.exit(0);
        }

        // Set document ID from user input
        this.documentId = docIdField.getText().trim();
        if (this.documentId.isEmpty()) {
            this.documentId = "default";
        }

        // Set client ID based on user selection
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

        // Use Document listener instead of KeyListener for better event handling
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
                    // For delete operations
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

        // Create status panel at bottom
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

                // Subscribe to specific document topic
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

                // Send sync request with document ID and client ID
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
            List<String> path = calculatePathForPosition(position);

            EditorMessage message = new EditorMessage();
            message.setType(EditorMessage.MessageType.OPERATION);
            message.setClientId(clientId);
            message.setDocumentId(documentId);
            message.setOperation(new Operation(Operation.Type.INSERT, text, path, System.currentTimeMillis(), clientId));
            
            System.out.println("⇨ Sending INSERT operation: " + text + " at position " + position);
            stompSession.send("/app/editor/operation", message);
        }
    }

    private void handleLocalDelete(int position, int length) {
        synchronized (documentLock) {
            // We need to get the content that was deleted
            // Since it's already gone from the textArea, we can't directly access it
            // This is a limitation of our current approach
            
            // For simplicity, we'll send a delete operation with the length
            // and assume the server state matches our local state
            List<String> path = calculatePathForPosition(position);

            EditorMessage message = new EditorMessage();
            message.setType(EditorMessage.MessageType.OPERATION);
            message.setClientId(clientId);
            message.setDocumentId(documentId);
            message.setOperation(new Operation(Operation.Type.DELETE, " ".repeat(length), path, System.currentTimeMillis(), clientId));
            
            System.out.println("⇨ Sending DELETE operation at position " + position + " length " + length);
            stompSession.send("/app/editor/operation", message);
        }
    }

    private void handleRemoteMessage(EditorMessage message) {
        // Verify the message is for our document
        if (!documentId.equals(message.getDocumentId())) {
            return; // Ignore messages for other documents
        }

        SwingUtilities.invokeLater(() -> {
            synchronized (documentLock) {
                try {
                    // Set flag to prevent our document listener from firing
                    isProcessingRemoteOperation.set(true);
                
                    if (message.getType() == EditorMessage.MessageType.OPERATION && 
                            !message.getOperation().getClientId().equals(clientId)) {  // Don't process own ops
                        Operation op = message.getOperation();
                        int pos = calculatePositionFromPath(op.getPath());
                
                        if (op.getType() == Operation.Type.INSERT) {
                            // Insert the content at the calculated position
                            textArea.insert(op.getContent(), pos);
                            System.out.println("Applied remote INSERT at position " + pos + ": '" + op.getContent() + "'");
                        } else if (op.getType() == Operation.Type.DELETE) {
                            // Delete content at the calculated position
                            int endPos = Math.min(pos + op.getContent().length(), textArea.getDocument().getLength());
                            textArea.replaceRange("", pos, endPos);
                            System.out.println("Applied remote DELETE at position " + pos + " to " + endPos);
                        }
                    } else if (message.getType() == EditorMessage.MessageType.SYNC_RESPONSE) {
                        // Set the entire document content
                        String content = message.getContent();
                        if (content != null) {
                            textArea.setText(content);
                            System.out.println("Applied SYNC_RESPONSE with content length: " + content.length());
                        } else {
                            textArea.setText("");
                            System.out.println("Applied SYNC_RESPONSE with null content");
                        }
                        // Place cursor at end of document
                        textArea.setCaretPosition(textArea.getDocument().getLength());
                    }
                } finally {
                    // Always reset the flag when done
                    isProcessingRemoteOperation.set(false);
                }
            }
        });
    }

    private List<String> calculatePathForPosition(int position) {
        List<String> path = new ArrayList<>();
        String document = textArea.getText();

        if (position == 0) path.add("start");
        else if (position >= document.length()) path.add("end");
        else {
            path.add("after-" + (int) document.charAt(position - 1));
            if (position < document.length()) {
                path.add("before-" + (int) document.charAt(position));
            }
        }

        return path;
    }

    private int calculatePositionFromPath(List<String> path) {
        if (path == null || path.isEmpty()) return 0;
        
        String document = textArea.getText();
        if (path.contains("start")) return 0;
        if (path.contains("end")) return document.length();

        for (int i = 0; i < document.length(); i++) {
            boolean matches = true;
            for (String segment : path) {
                if (segment.startsWith("after-")) {
                    int charCode = Integer.parseInt(segment.substring(6));
                    if (i == 0 || (int) document.charAt(i - 1) != charCode) {
                        matches = false;
                        break;
                    }
                } else if (segment.startsWith("before-")) {
                    int charCode = Integer.parseInt(segment.substring(7));
                    if (i >= document.length() || (int) document.charAt(i) != charCode) {
                        matches = false;
                        break;
                    }
                }
            }
            if (matches) return i;
        }

        return document.length(); // Default to end of document if path can't be resolved
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(EditorClient::new);
    }
}