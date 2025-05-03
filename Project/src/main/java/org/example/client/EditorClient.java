package org.example.client;

import org.example.crdt.Operation;
import org.example.crdt.Position;
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
import javax.swing.text.BadLocationException;
import javax.swing.text.DefaultHighlighter;
import javax.swing.text.Highlighter;
import java.awt.*;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.StringSelection;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.lang.reflect.Type;

public class EditorClient {
    private JFrame frame;
    private JTextArea textArea;
    private JLabel statusLabel;
    private StompSession stompSession;
    private String clientId;
    private String documentId;
    private int cursorPosition;
    private boolean isProcessingRemoteOperation;
    private Queue<Runnable> pendingLocalOperations;
    private Map<String, Position> positionCache;
    private Map<String, Highlighter.Highlight> cursorHighlights;

    public EditorClient() {
        this.clientId = UUID.randomUUID().toString();
        this.cursorPosition = 0;
        this.isProcessingRemoteOperation = false;
        this.pendingLocalOperations = new ConcurrentLinkedQueue<>();
        this.positionCache = new LinkedHashMap<>(100, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry eldest) {
                return size() > 100; // Limit cache to 100 entries
            }
        };
        this.cursorHighlights = new ConcurrentHashMap<>();
        this.documentId = "Pending..."; // Temporary until connection

        initializeUI();
        connectToWebSocket();
    }

    private void initializeUI() {
        frame = new JFrame("Collaborative Editor - " + clientId.substring(0, 8));
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setSize(800, 600);

        textArea = new JTextArea();
        textArea.setFont(new Font("Monospaced", Font.PLAIN, 14));
        textArea.setLineWrap(true);
        textArea.setWrapStyleWord(true);

        JMenuBar menuBar = new JMenuBar();
        JMenu fileMenu = new JMenu("File");
        JMenuItem importItem = new JMenuItem("Import TXT");
        JMenuItem exportItem = new JMenuItem("Export TXT");
        JMenuItem syncItem = new JMenuItem("Force Sync");
        JMenuItem copyIdItem = new JMenuItem("Copy Document ID");
        importItem.addActionListener(e -> importFile());
        exportItem.addActionListener(e -> exportFile());
        syncItem.addActionListener(e -> requestFullSync());
        copyIdItem.addActionListener(e -> copyDocumentIdToClipboard());
        fileMenu.add(importItem);
        fileMenu.add(exportItem);
        fileMenu.add(syncItem);
        fileMenu.add(copyIdItem);
        menuBar.add(fileMenu);

        statusLabel = new JLabel("Document ID: " + documentId + " | Clients: 1");
        JPanel statusPanel = new JPanel(new BorderLayout());
        statusPanel.add(statusLabel, BorderLayout.WEST);

        textArea.addKeyListener(new java.awt.event.KeyAdapter() {
            @Override
            public void keyTyped(java.awt.event.KeyEvent e) {
                if (!isProcessingRemoteOperation) {
                    handleLocalEdit(e.getKeyChar());
                } else {
                    pendingLocalOperations.offer(() -> handleLocalEdit(e.getKeyChar()));
                }
            }

            @Override
            public void keyPressed(java.awt.event.KeyEvent e) {
                if (e.getKeyCode() == java.awt.event.KeyEvent.VK_BACK_SPACE && !isProcessingRemoteOperation) {
                    handleLocalDelete();
                } else if (e.getKeyCode() == java.awt.event.KeyEvent.VK_BACK_SPACE) {
                    pendingLocalOperations.offer(EditorClient.this::handleLocalDelete);
                }
            }
        });

        textArea.addCaretListener(e -> updateCursorPosition());

        JScrollPane scrollPane = new JScrollPane(textArea);
        frame.setLayout(new BorderLayout());
        frame.add(scrollPane, BorderLayout.CENTER);
        frame.add(statusPanel, BorderLayout.SOUTH);
        frame.setJMenuBar(menuBar);
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

                session.subscribe("/topic/editor", new StompFrameHandler() {
                    @Override
                    public Type getPayloadType(StompHeaders headers) {
                        return EditorMessage.class;
                    }

                    @Override
                    public void handleFrame(StompHeaders headers, Object payload) {
                        EditorMessage msg = (EditorMessage) payload;
                        handleRemoteMessage(msg);
                    }
                });

                // Prompt for document ID after connection
                SwingUtilities.invokeLater(() -> {
                    String input = JOptionPane.showInputDialog(frame,
                            "Enter Document ID to join an existing session or leave blank to create a new document:",
                            "Join Document", JOptionPane.QUESTION_MESSAGE);
                    if (input == null || input.trim().isEmpty()) {
                        documentId = UUID.randomUUID().toString(); // Temporary
                        requestNewDocument();
                    } else {
                        documentId = input;
                        EditorMessage syncRequest = new EditorMessage();
                        syncRequest.setType(EditorMessage.MessageType.SYNC_REQUEST);
                        syncRequest.setClientId(clientId);
                        syncRequest.setDocumentId(documentId);
                        session.send("/app/editor/operation", syncRequest);
                        statusLabel.setText("Document ID: " + documentId + " | Clients: 1");
                    }
                });
            }

            @Override
            public void handleTransportError(StompSession session, Throwable exception) {
                SwingUtilities.invokeLater(() ->
                        statusLabel.setText("Disconnected: " + exception.getMessage() + " | Document ID: " + documentId));
                JOptionPane.showMessageDialog(frame, "Connection error: " + exception.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
            }
        });
    }

    private void copyDocumentIdToClipboard() {
        if (documentId == null || documentId.equals("Pending...")) {
            JOptionPane.showMessageDialog(frame, "No document ID available yet.", "Error", JOptionPane.ERROR_MESSAGE);
            return;
        }
        StringSelection selection = new StringSelection(documentId);
        Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
        clipboard.setContents(selection, null);
        JOptionPane.showMessageDialog(frame, "Document ID copied to clipboard: " + documentId, "Success", JOptionPane.INFORMATION_MESSAGE);
    }

    private void handleLocalEdit(char c) {
        if (!Character.isDefined(c) || c == '\n') return; // Ignore invalid chars
        Position pos = calculatePositionForIndex(cursorPosition);
        List<String> path = positionToPath(pos);

        Operation op = new Operation(Operation.Type.INSERT, String.valueOf(c), path, clientId);
        EditorMessage message = new EditorMessage();
        message.setType(EditorMessage.MessageType.OPERATION);
        message.setOperations(List.of(op));
        message.setClientId(clientId);
        message.setDocumentId(documentId);
        stompSession.send("/app/editor/operation", message);

        cursorPosition++;
    }

    private void handleLocalDelete() {
        if (cursorPosition == 0 || cursorPosition > textArea.getText().length()) {
            return;
        }

        Position pos = calculatePositionForIndex(cursorPosition - 1);
        List<String> path = positionToPath(pos);
        String currentText = textArea.getText();
        if (cursorPosition - 1 >= currentText.length()) {
            return;
        }
        String contentToDelete = currentText.substring(cursorPosition - 1, cursorPosition);

        Operation op = new Operation(Operation.Type.DELETE, contentToDelete, path, clientId);
        EditorMessage message = new EditorMessage();
        message.setType(EditorMessage.MessageType.OPERATION);
        message.setOperations(List.of(op));
        message.setClientId(clientId);
        message.setDocumentId(documentId);
        stompSession.send("/app/editor/operation", message);

        cursorPosition--;
    }

    private void updateCursorPosition() {
        int newPos = textArea.getCaretPosition();
        if (newPos != cursorPosition) {
            cursorPosition = newPos;
            EditorMessage cursorMessage = new EditorMessage();
            cursorMessage.setType(EditorMessage.MessageType.CURSOR_UPDATE);
            cursorMessage.setClientId(clientId);
            cursorMessage.setDocumentId(documentId);
            cursorMessage.setCursorPosition(cursorPosition);
            stompSession.send("/app/editor/operation", cursorMessage);
        }
    }

    private void handleRemoteMessage(EditorMessage message) {
        SwingUtilities.invokeLater(() -> {
            isProcessingRemoteOperation = true;
            try {
                if (message.getType() == EditorMessage.MessageType.OPERATION &&
                        !message.getClientId().equals(clientId)) {
                    for (Operation op : message.getOperations()) {
                        int pos = calculateIndexFromPosition(pathToPosition(op.getPath()));
                        if (op.getType() == Operation.Type.INSERT) {
                            textArea.insert(op.getContent(), pos);
                            if (pos <= cursorPosition) {
                                cursorPosition += op.getContent().length();
                            }
                        } else if (op.getType() == Operation.Type.DELETE) {
                            String currentText = textArea.getText();
                            if (pos >= 0 && pos < currentText.length()) {
                                int endPos = Math.min(pos + op.getContent().length(), currentText.length());
                                String toDelete = currentText.substring(pos, endPos);
                                if (toDelete.equals(op.getContent())) {
                                    textArea.replaceRange("", pos, endPos);
                                    if (pos < cursorPosition) {
                                        cursorPosition = Math.max(0, cursorPosition - op.getContent().length());
                                    }
                                } else {
                                    requestFullSync();
                                }
                            }
                        }
                    }
                } else if (message.getType() == EditorMessage.MessageType.SYNC_RESPONSE) {
                    textArea.setText(message.getContent());
                    cursorPosition = Math.min(cursorPosition, message.getContent().length());
                    textArea.setCaretPosition(cursorPosition);
                    positionCache.clear();
                } else if (message.getType() == EditorMessage.MessageType.CREATE_DOCUMENT_RESPONSE) {
                    documentId = message.getDocumentId();
                    statusLabel.setText("Document ID: " + documentId + " | Clients: 1");

                    // Custom dialog with Copy ID button
                    JDialog dialog = new JDialog(frame, "Document Created", true);
                    dialog.setLayout(new BorderLayout());
                    dialog.setSize(400, 150);
                    dialog.setLocationRelativeTo(frame);

                    JLabel messageLabel = new JLabel("New document created. Share this ID: " + documentId);
                    messageLabel.setHorizontalAlignment(SwingConstants.CENTER);
                    dialog.add(messageLabel, BorderLayout.CENTER);

                    JPanel buttonPanel = new JPanel();
                    JButton copyButton = new JButton("Copy ID");
                    JButton okButton = new JButton("OK");
                    copyButton.addActionListener(e -> copyDocumentIdToClipboard());
                    okButton.addActionListener(e -> dialog.dispose());
                    buttonPanel.add(copyButton);
                    buttonPanel.add(okButton);
                    dialog.add(buttonPanel, BorderLayout.SOUTH);

                    dialog.setVisible(true);

                    EditorMessage syncRequest = new EditorMessage();
                    syncRequest.setType(EditorMessage.MessageType.SYNC_REQUEST);
                    syncRequest.setClientId(clientId);
                    syncRequest.setDocumentId(documentId);
                    stompSession.send("/app/editor/operation", syncRequest);
                } else if (message.getType() == EditorMessage.MessageType.CURSOR_UPDATE &&
                        !message.getClientId().equals(clientId)) {
                    updateRemoteCursor(message.getClientId(), message.getCursorPosition());
                }
            } finally {
                isProcessingRemoteOperation = false;
                processPendingOperations();
                updateStatus();
            }
        });
    }

    private void updateRemoteCursor(String clientId, int position) {
        Highlighter highlighter = textArea.getHighlighter();
        highlighter.removeHighlight(cursorHighlights.getOrDefault(clientId, null));
        try {
            if (position >= 0 && position <= textArea.getText().length()) {
                Color color = new Color(clientId.hashCode() % 256, 128, 128, 128); // Semi-transparent color
                Highlighter.Highlight highlight = (Highlighter.Highlight) highlighter.addHighlight(
                        position, position + 1, new DefaultHighlighter.DefaultHighlightPainter(color));
                cursorHighlights.put(clientId, highlight);
            }
        } catch (BadLocationException e) {
            System.err.println("Invalid cursor position: " + position);
        }
    }

    private void updateStatus() {
        statusLabel.setText("Document ID: " + documentId + " | Clients: " + (cursorHighlights.size() + 1));
    }

    private void requestFullSync() {
        EditorMessage syncRequest = new EditorMessage();
        syncRequest.setType(EditorMessage.MessageType.SYNC_REQUEST);
        syncRequest.setClientId(clientId);
        syncRequest.setDocumentId(documentId);
        stompSession.send("/app/editor/operation", syncRequest);
    }

    private void processPendingOperations() {
        while (!pendingLocalOperations.isEmpty() && !isProcessingRemoteOperation) {
            Runnable operation = pendingLocalOperations.poll();
            if (operation != null) {
                operation.run();
            }
        }
    }

    private Position calculatePositionForIndex(int index) {
        String document = textArea.getText();
        if (document.isEmpty() || index <= 0) {
            return new Position();
        }

        String cacheKey = index + ":" + document.hashCode();
        return positionCache.computeIfAbsent(cacheKey, k -> {
            // Generate a fractional index based on position in document
            double fraction = (double) index / (document.length() + 1);
            List<Position.Identifier> identifiers = new ArrayList<>();
            identifiers.add(new Position.Identifier(fraction, clientId));
            return new Position(identifiers);
        });
    }

    private List<String> positionToPath(Position pos) {
        List<String> path = new ArrayList<>();
        path.add("root");
        for (Position.Identifier id : pos.getPath()) {
            path.add(id.getFraction() + ":" + id.getClientId());
        }
        return path;
    }

    private Position pathToPosition(List<String> path) {
        List<Position.Identifier> identifiers = new ArrayList<>();
        for (String segment : path) {
            if (segment.equals("root")) continue;
            String[] parts = segment.split(":");
            if (parts.length == 2) {
                identifiers.add(new Position.Identifier(Double.parseDouble(parts[0]), parts[1]));
            }
        }
        return new Position(identifiers);
    }

    private int calculateIndexFromPosition(Position pos) {
        String document = textArea.getText();
        if (pos.getPath().isEmpty()) {
            return 0;
        }
        // Approximate index based on last fraction
        double fraction = pos.getPath().get(pos.getPath().size() - 1).getFraction();
        return Math.min((int) (fraction * (document.length() + 1)), document.length());
    }

    private void importFile() {
        JFileChooser fileChooser = new JFileChooser();
        fileChooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("Text Files", "txt"));
        if (fileChooser.showOpenDialog(frame) == JFileChooser.APPROVE_OPTION) {
            try {
                String content = Files.readString(fileChooser.getSelectedFile().toPath());
                textArea.setText(content);
                cursorPosition = 0;
                textArea.setCaretPosition(0);
                positionCache.clear();

                EditorMessage syncRequest = new EditorMessage();
                syncRequest.setType(EditorMessage.MessageType.SYNC_REQUEST);
                syncRequest.setClientId(clientId);
                syncRequest.setContent(content);
                syncRequest.setDocumentId(documentId);
                stompSession.send("/app/editor/operation", syncRequest);
            } catch (IOException e) {
                JOptionPane.showMessageDialog(frame, "Error importing file: " + e.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    private void exportFile() {
        JFileChooser fileChooser = new JFileChooser();
        fileChooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("Text Files", "txt"));
        if (fileChooser.showSaveDialog(frame) == JFileChooser.APPROVE_OPTION) {
            try {
                Path path = fileChooser.getSelectedFile().toPath();
                if (!path.toString().endsWith(".txt")) {
                    path = Path.of(path.toString() + ".txt");
                }
                Files.writeString(path, textArea.getText());
                JOptionPane.showMessageDialog(frame, "File exported successfully", "Success", JOptionPane.INFORMATION_MESSAGE);
            } catch (IOException e) {
                JOptionPane.showMessageDialog(frame, "Error exporting file: " + e.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    private void requestNewDocument() {
        EditorMessage createDocMessage = new EditorMessage();
        createDocMessage.setType(EditorMessage.MessageType.CREATE_DOCUMENT);
        createDocMessage.setClientId(clientId);
        stompSession.send("/app/editor/operation", createDocMessage);
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(EditorClient::new);
    }
}