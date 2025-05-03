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
                // Validate the position is within bounds
                if (position < 0) {
                    position = 0;
                } else if (position > characterIds.size()) {
                    position = characterIds.size();
                }
                
                // Generate unique IDs for each inserted character
                List<String> charIds = new ArrayList<>();
                long timestamp = System.currentTimeMillis();
                for (int i = 0; i < text.length(); i++) {
                    charIds.add(clientId + ":" + timestamp + ":" + i);
                }

                // Calculate path for insertion
                List<String> path = calculatePathForPosition(position);

                // Create operation message
                EditorMessage message = new EditorMessage();
                message.setType(EditorMessage.MessageType.OPERATION);
                message.setClientId(clientId);
                message.setDocumentId(documentId);
                message.setOperation(new Operation(Operation.Type.INSERT, text, path, timestamp, clientId));

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
    
                // Calculate end position (ensuring it doesn't exceed array bounds)
                int endPos = Math.min(position + length, characterIds.size());
                
                // Get character IDs for the deleted range
                List<String> deletedCharIds = new ArrayList<>(characterIds.subList(position, endPos));
                
                // Get deleted content for reference
                String deletedContent = "";
                try {
                    // Use the textArea.getText in case we're mid-operation and the text doesn't 
                    // perfectly match our character IDs
                    deletedContent = previousContent.substring(position, position + length);
                } catch (StringIndexOutOfBoundsException e) {
                    System.err.println("Error retrieving deleted content from previousContent: " + e.getMessage());
                    // Fallback to getting text directly from textArea
                    try {
                        deletedContent = textArea.getText(position, length);
                    } catch (BadLocationException be) {
                        System.err.println("Error retrieving deleted content: " + be.getMessage());
                    }
                }
    
                // Create proper path representation for deletion
                List<String> path = new ArrayList<>();
                for (String charId : deletedCharIds) {
                    path.add("char-" + charId);
                }
    
                // Create and send operation
                EditorMessage message = new EditorMessage();
                message.setType(EditorMessage.MessageType.OPERATION);
                message.setClientId(clientId);
                message.setDocumentId(documentId);
                message.setOperation(new Operation(
                    Operation.Type.DELETE, 
                    deletedContent, 
                    path, 
                    System.currentTimeMillis(), 
                    clientId
                ));
    
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
                            // Handle remote insert
                            int pos = calculatePositionFromPath(transformedOp.getPath());
                            
                            // Ensure position is valid
                            if (pos < 0) pos = 0;
                            if (pos > textArea.getText().length()) pos = textArea.getText().length();
                            
                            // Create character IDs for the inserted text
                            List<String> charIds = new ArrayList<>();
                            for (int i = 0; i < transformedOp.getContent().length(); i++) {
                                charIds.add(transformedOp.getClientId() + ":" + transformedOp.getTimestamp() + ":" + i);
                            }
                            
                            // Insert the text
                            textArea.insert(transformedOp.getContent(), pos);
                            
                            // Update character IDs
                            characterIds.addAll(pos, charIds);
                            
                            // Adjust caret position if needed
                            if (pos <= caretPos) {
                                caretPos += transformedOp.getContent().length();
                            }
                            
                        } else if (transformedOp.getType() == Operation.Type.DELETE) {
                            // Handle remote delete
                            List<Integer> positionsToDelete = new ArrayList<>();
                            
                            // Find positions of characters to delete
                            for (String pathEntry : transformedOp.getPath()) {
                                if (pathEntry.startsWith("char-")) {
                                    String charId = pathEntry.substring(5);
                                    int index = characterIds.indexOf(charId);
                                    if (index != -1) {
                                        positionsToDelete.add(index);
                                    }
                                }
                            }
                            
                            // If no positions found, try to infer from content
                            if (positionsToDelete.isEmpty() && transformedOp.getContent() != null) {
                                String content = textArea.getText();
                                int searchPos = content.indexOf(transformedOp.getContent());
                                if (searchPos >= 0) {
                                    for (int i = 0; i < transformedOp.getContent().length(); i++) {
                                        positionsToDelete.add(searchPos + i);
                                    }
                                }
                            }
                            
                            // Sort positions in descending order for correct deletion
                            Collections.sort(positionsToDelete, Collections.reverseOrder());
                            
                            // Delete characters one by one
                            for (int pos : positionsToDelete) {
                                if (pos >= 0 && pos < textArea.getText().length()) {
                                    textArea.replaceRange("", pos, pos + 1);
                                    if (pos < characterIds.size()) {
                                        characterIds.remove(pos);
                                    }
                                    
                                    // Adjust caret position
                                    if (pos < caretPos) {
                                        caretPos--;
                                    }
                                }
                            }
                        }
                        
                        // Update previous content and set caret position
                        previousContent = textArea.getText();
                        textArea.setCaretPosition(Math.min(caretPos, textArea.getText().length()));
                        
                    } else if (message.getType() == EditorMessage.MessageType.SYNC_RESPONSE) {
                        // Handle sync response
                        String content = message.getContent();
                        if (content != null && !content.equals(textArea.getText())) {
                            textArea.setText(content);
                            previousContent = content;
                            
                            // Reset character IDs
                            characterIds.clear();
                            for (int i = 0; i < content.length(); i++) {
                                characterIds.add("sync:" + i);
                            }
                        } else if (content == null) {
                            textArea.setText("");
                            previousContent = "";
                            characterIds.clear();
                        }
                        
                        // Set caret position
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
            // Check for special cases
            if (position <= 0) {
                path.add("start");
            } else if (position >= characterIds.size()) {
                path.add("end");
            } else {
                // Regular case - specify position relative to previous character
                path.add("after-" + characterIds.get(position - 1));
                
                // Add additional context for better positioning
                if (position > 1) {
                    path.add("after-" + characterIds.get(position - 2));
                }
                if (position < characterIds.size() - 1) {
                    path.add("before-" + characterIds.get(position));
                }
            }
        }
        return path;
    }

    private int calculatePositionFromPath(List<String> path) {
        synchronized (documentLock) {
            if (path == null || path.isEmpty()) return 0;
            
            // Handle special cases
            if (path.contains("start")) return 0;
            if (path.contains("end")) return characterIds.size();
            
            // Process path entries to find position
            for (String pathEntry : path) {
                // Handle direct character reference
                if (pathEntry.startsWith("char-")) {
                    String charId = pathEntry.substring(5);
                    int index = characterIds.indexOf(charId);
                    if (index != -1) {
                        return index;
                    }
                } 
                // Handle position after a character
                else if (pathEntry.startsWith("after-")) {
                    String charId = pathEntry.substring(6);
                    int index = characterIds.indexOf(charId);
                    if (index != -1) {
                        return index + 1;
                    }
                }
                // Handle position before a character
                else if (pathEntry.startsWith("before-")) {
                    String charId = pathEntry.substring(7);
                    int index = characterIds.indexOf(charId);
                    if (index != -1) {
                        return index;
                    }
                }
            }
            
            // Fallback to end of document
            return characterIds.size();
        }
    }

    private Operation transformOperation(Operation op) {
        synchronized (documentLock) {
            if (op.getType() == Operation.Type.INSERT) {
                // Transform insert operation
                int pos = calculatePositionFromPath(op.getPath());
                boolean needsTransformation = false;
                
                // Check if we need to transform this insert
                for (int i = 0; i < Math.min(pos, characterIds.size()); i++) {
                    String[] parts = characterIds.get(i).split(":");
                    if (parts.length > 1 && 
                        op.getClientId().compareTo(parts[0]) > 0 && 
                        op.getTimestamp() - Long.parseLong(parts[1]) < 1000) {
                        needsTransformation = true;
                        break;
                    }
                }
                
                if (needsTransformation) {
                    // Find new position based on character IDs
                    int newPos = pos;
                    while (newPos > 0) {
                        String[] parts = characterIds.get(newPos - 1).split(":");
                        if (parts.length > 1 && 
                            (op.getClientId().compareTo(parts[0]) < 0 || 
                             (op.getClientId().equals(parts[0]) && op.getTimestamp() <= Long.parseLong(parts[1])))) {
                            break;
                        }
                        newPos--;
                    }
                    
                    // Create new path
                    List<String> newPath = calculatePathForPosition(newPos);
                    return new Operation(op.getType(), op.getContent(), newPath, 
                                         op.getTimestamp(), op.getClientId());
                }
            } 
            else if (op.getType() == Operation.Type.DELETE) {
                // For deletions, we need to ensure the correct characters are deleted
                List<String> newPath = new ArrayList<>();
                
                for (String pathEntry : op.getPath()) {
                    if (pathEntry.startsWith("char-")) {
                        String charId = pathEntry.substring(5);
                        // Keep the entry if character exists or add alternative reference
                        if (characterIds.contains(charId)) {
                            newPath.add(pathEntry);
                        } else {
                            // Try to locate nearby character
                            for (String id : characterIds) {
                                if (id.startsWith(charId.split(":")[0])) {
                                    newPath.add("char-" + id);
                                    break;
                                }
                            }
                        }
                    } else {
                        newPath.add(pathEntry);
                    }
                }
                
                // If we changed the path, create new operation
                if (!newPath.equals(op.getPath())) {
                    return new Operation(op.getType(), op.getContent(), newPath,
                                         op.getTimestamp(), op.getClientId());
                }
            }
            
            // No transformation needed
            return op;
        }
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(EditorClient::new);
    }
}