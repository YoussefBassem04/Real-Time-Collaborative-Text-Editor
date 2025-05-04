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
import javax.swing.event.UndoableEditEvent;
import javax.swing.event.UndoableEditListener;
import javax.swing.text.AbstractDocument;
import javax.swing.text.BadLocationException;
import javax.swing.undo.*;
import java.awt.*;
import java.lang.reflect.Type;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.List;

public class EditorClient {
    private JFrame frame;
    private JTextArea textArea;
    private StompSession stompSession;
    private String clientId;
    private String documentId;
    private AtomicBoolean isProcessingRemoteOperation = new AtomicBoolean(false);
    private final Object documentLock = new Object();
    private String previousContent = "";
    private List<String> nodeIds = new ArrayList<>();
    private UndoManager undoManager = new UndoManager();
    private JButton undoButton;
    private JButton redoButton;
    private CollaborativeEdit lastEdit; // Track the last edit for nodeId assignment

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

        if (result == JOptionPane.OK_CANCEL_OPTION) {
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
        undoButton = new JButton("Undo");
        redoButton = new JButton("Redo");
        textArea = new JTextArea();
        textArea.setFont(new Font("Monospaced", Font.PLAIN, 14));
        textArea.setLineWrap(true);
        textArea.setWrapStyleWord(true);

        // Custom UndoableEditListener to wrap edits in CollaborativeEdit
        textArea.getDocument().addUndoableEditListener(new UndoableEditListener() {
            @Override
            public void undoableEditHappened(UndoableEditEvent e) {
                if (!isProcessingRemoteOperation.get()) {
                    lastEdit = new CollaborativeEdit(e.getEdit());
                    undoManager.addEdit(lastEdit);
                }
                updateButtonStates();
            }
        });

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

        undoButton.addActionListener(e -> {
            try {
                if (undoManager.canUndo()) {
                    undoManager.undo();
                    System.out.println("Performed undo: " + undoManager.getUndoPresentationName());
                    updateButtonStates();
                }
            } catch (CannotUndoException ex) {
                ex.printStackTrace();
            }
        });

        redoButton.addActionListener(e -> {
            try {
                if (undoManager.canRedo()) {
                    undoManager.redo();
                    System.out.println("Performed redo: " + undoManager.getRedoPresentationName());
                    updateButtonStates();
                }
            } catch (CannotRedoException ex) {
                ex.printStackTrace();
            }
        });

        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttonPanel.add(undoButton);
        buttonPanel.add(redoButton);

        frame.add(buttonPanel, BorderLayout.NORTH);
        JScrollPane scrollPane = new JScrollPane(textArea);
        JPanel statusPanel = new JPanel(new BorderLayout());
        JLabel statusLabel = new JLabel(" Connected as: " + clientId.substring(0, 8) + " | Document: " + documentId);
        statusPanel.add(statusLabel, BorderLayout.WEST);

        frame.add(scrollPane, BorderLayout.CENTER);
        frame.add(statusPanel, BorderLayout.SOUTH);
        frame.setVisible(true);
        updateButtonStates();
    }

    private void updateButtonStates() {
        undoButton.setEnabled(undoManager.canUndo());
        redoButton.setEnabled(undoManager.canRedo());
        undoButton.setToolTipText(undoManager.canUndo() ? "Undo " + undoManager.getUndoPresentationName() : null);
        redoButton.setToolTipText(undoManager.canRedo() ? "Redo " + undoManager.getRedoPresentationName() : null);
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
                List<String> path = calculatePathForPosition(position);
                long timestamp = System.currentTimeMillis();
                Operation op = new Operation(
                        Operation.Type.INSERT,
                        text,
                        path,
                        timestamp,
                        clientId);

                EditorMessage message = new EditorMessage();
                message.setType(EditorMessage.MessageType.OPERATION);
                message.setClientId(clientId);
                message.setDocumentId(documentId);
                message.setOperation(op);

                List<String> tempNodeIds = new ArrayList<>();
                for (int i = 0; i < text.length(); i++) {
                    tempNodeIds.add("temp:" + timestamp + ":" + i);
                }
                nodeIds.addAll(position, tempNodeIds);
                if (lastEdit != null && lastEdit.isInsert && lastEdit.position == position
                        && lastEdit.content.equals(text)) {
                    lastEdit.setAffectedNodeIds(tempNodeIds);
                }
                previousContent = textArea.getText();

                System.out.println("⇨ Sending INSERT operation: '" + text + "' at position " + position +
                        ", Path: " + path);
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
                List<String> deletedNodeIds = new ArrayList<>();
                StringBuilder deletedText = new StringBuilder();
                for (int i = 0; i < length && position + i < nodeIds.size(); i++) {
                    String nodeId = nodeIds.get(position + i);
                    List<String> path = new ArrayList<>();
                    path.add(nodeId);
                    String deletedChar = String.valueOf(previousContent.charAt(position + i));
                    Operation op = new Operation(
                            Operation.Type.DELETE,
                            deletedChar,
                            path,
                            System.currentTimeMillis(),
                            clientId);

                    EditorMessage message = new EditorMessage();
                    message.setType(EditorMessage.MessageType.OPERATION);
                    message.setClientId(clientId);
                    message.setDocumentId(documentId);
                    message.setOperation(op);

                    deletedNodeIds.add(nodeId);
                    deletedText.append(deletedChar);

                    System.out.println("⇨ Sending DELETE operation for char '" + deletedChar +
                            "' at position " + (position + i) + ", NodeId: " + nodeId);
                    stompSession.send("/app/editor/operation", message);
                }

                nodeIds.subList(position, Math.min(position + length, nodeIds.size())).clear();
                if (lastEdit != null && !lastEdit.isInsert && lastEdit.position == position) {
                    lastEdit.setAffectedNodeIds(deletedNodeIds);
                    lastEdit.setContent(deletedText.toString());
                }
                previousContent = textArea.getText();
            } catch (Exception e) {
                System.err.println("Error handling delete: " + e.getMessage());
                e.printStackTrace();
            }
        }
    }

    private void handleRemoteMessage(EditorMessage message) {
        if (!documentId.equals(message.getDocumentId()))
            return;

        SwingUtilities.invokeLater(() -> {
            synchronized (documentLock) {
                try {
                    isProcessingRemoteOperation.set(true);
                    int caretPos = textArea.getCaretPosition();

                    if (message.getType() == EditorMessage.MessageType.OPERATION) {
                        Operation op = message.getOperation();
                        int pos = calculatePositionFromPath(op.getPath());

                        System.out.println("Applying remote operation: " + op.getType() +
                                ", Content: '" + op.getContent() + "', Path: " + op.getPath() +
                                ", Position: " + pos);

                        if (op.getType() == Operation.Type.INSERT) {
                            List<String> serverNodeIds = message.getNodeIds();
                            if (serverNodeIds == null || serverNodeIds.isEmpty()
                                    || serverNodeIds.size() != op.getContent().length()) {
                                serverNodeIds = new ArrayList<>();
                                long timestamp = op.getTimestamp();
                                for (int i = 0; i < op.getContent().length(); i++) {
                                    serverNodeIds.add(op.getClientId() + ":" + (timestamp + i));
                                }
                            }
                            int tempIndex = nodeIds.indexOf("temp:" + op.getTimestamp() + ":0");
                            if (tempIndex >= 0) {
                                nodeIds.subList(tempIndex, tempIndex + op.getContent().length()).clear();
                                nodeIds.addAll(tempIndex, serverNodeIds);
                            } else if (!op.getClientId().equals(clientId)) {
                                textArea.insert(op.getContent(), pos);
                                nodeIds.addAll(pos, serverNodeIds);
                                if (pos <= caretPos) {
                                    textArea.setCaretPosition(caretPos + op.getContent().length());
                                }
                            }
                        } else if (op.getType() == Operation.Type.DELETE && !op.getClientId().equals(clientId)) {
                            if (pos >= 0 && pos < nodeIds.size()) {
                                textArea.replaceRange("", pos, pos + 1);
                                nodeIds.remove(pos);
                                if (pos < caretPos) {
                                    textArea.setCaretPosition(Math.max(pos, caretPos - 1));
                                }
                            } else {
                                System.err.println(
                                        "Invalid DELETE position: " + pos + ", nodeIds size: " + nodeIds.size());
                            }
                        }
                        previousContent = textArea.getText();
                    } else if (message.getType() == EditorMessage.MessageType.SYNC_RESPONSE) {
                        String content = message.getContent();
                        List<String> serverNodeIds = message.getNodeIds();
                        nodeIds.clear();
                        if (content != null) {
                            textArea.setText(content);
                            previousContent = content;
                            if (serverNodeIds != null && serverNodeIds.size() == content.length()) {
                                nodeIds.addAll(serverNodeIds);
                            } else {
                                for (int i = 0; i < content.length(); i++) {
                                    nodeIds.add("sync:" + i);
                                }
                            }
                        } else {
                            textArea.setText("");
                            previousContent = "";
                        }
                        textArea.setCaretPosition(Math.min(caretPos, textArea.getDocument().getLength()));
                    }
                    updateButtonStates();
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
        synchronized (documentLock) {
            List<String> path = new ArrayList<>();
            if (position == 0) {
                path.add("0:" + clientId);
            } else if (position >= nodeIds.size()) {
                path.add((nodeIds.size()) + ":" + clientId);
            } else {
                path.add(position + ":" + clientId);
            }
            return path;
        }
    }

    private int calculatePositionFromPath(List<String> path) {
        synchronized (documentLock) {
            if (path == null || path.isEmpty())
                return 0;
            String pathElement = path.get(0);
            int index = nodeIds.indexOf(pathElement);
            if (index >= 0) {
                return index;
            }
            try {
                String[] parts = pathElement.split(":");
                int pathPos = Integer.parseInt(parts[0]);
                return Math.min(pathPos, nodeIds.size());
            } catch (NumberFormatException e) {
                System.err.println("Invalid path format: " + pathElement);
                return nodeIds.size();
            }
        }
    }

    private Operation transformOperation(Operation op) {
        synchronized (documentLock) {
            return op;
        }
    }

    // Custom UndoableEdit to handle collaborative operations
    private class CollaborativeEdit extends AbstractUndoableEdit {
        private final UndoableEdit originalEdit;
        private List<String> affectedNodeIds;
        private String content;
        private int position;
        private boolean isInsert;

        public CollaborativeEdit(UndoableEdit edit) {
            this.originalEdit = edit;
            this.affectedNodeIds = new ArrayList<>();
            try {
                if (edit instanceof AbstractDocument.DefaultDocumentEvent) {
                    AbstractDocument.DefaultDocumentEvent docEvent = (AbstractDocument.DefaultDocumentEvent) edit;
                    position = docEvent.getOffset();
                    if (docEvent.getType() == DocumentEvent.EventType.INSERT) {
                        isInsert = true;
                        content = textArea.getText(position, docEvent.getLength());
                    } else if (docEvent.getType() == DocumentEvent.EventType.REMOVE) {
                        isInsert = false;
                        content = previousContent.substring(position, position + docEvent.getLength());
                    }
                }
            } catch (BadLocationException e) {
                e.printStackTrace();
            }
        }

        public void setAffectedNodeIds(List<String> nodeIds) {
            this.affectedNodeIds = new ArrayList<>(nodeIds);
        }

        public void setContent(String content) {
            this.content = content;
        }

        @Override
        public void undo() throws CannotUndoException {
            synchronized (documentLock) {
                try {
                    super.undo();
                    if (isInsert) {
                        System.out.println("Undoing INSERT: Removing '" + content + "' at position " + position);
                        textArea.getDocument().remove(position, content.length());
                        nodeIds.subList(position, position + content.length()).clear();
                    } else {
                        System.out.println("Undoing DELETE: Restoring '" + content + "' at position " + position);
                        textArea.getDocument().insertString(position, content, null);
                        nodeIds.addAll(position, affectedNodeIds);
                    }
                    previousContent = textArea.getText();
                } catch (BadLocationException e) {
                    throw new CannotUndoException();
                }
            }
        }

        @Override
        public void redo() throws CannotRedoException {
            synchronized (documentLock) {
                try {
                    super.redo();
                    if (isInsert) {
                        System.out.println("Redoing INSERT: Reinserting '" + content + "' at position " + position);
                        textArea.getDocument().insertString(position, content, null);
                        nodeIds.addAll(position, affectedNodeIds);
                    } else {
                        System.out.println("Redoing DELETE: Removing '" + content + "' at position " + position);
                        textArea.getDocument().remove(position, content.length());
                        nodeIds.subList(position, position + content.length()).clear();
                    }
                    previousContent = textArea.getText();
                } catch (BadLocationException e) {
                    throw new CannotRedoException();
                }
            }
        }

        @Override
        public String getPresentationName() {
            return isInsert ? "insertion" : "deletion";
        }

        @Override
        public String getUndoPresentationName() {
            return "Undo " + getPresentationName() + " of '" + content + "'";
        }

        @Override
        public String getRedoPresentationName() {
            return "Redo " + getPresentationName() + " of '" + content + "'";
        }

        @Override
        public boolean canUndo() {
            return originalEdit.canUndo();
        }

        @Override
        public boolean canRedo() {
            return originalEdit.canRedo();
        }
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(EditorClient::new);
    }
}