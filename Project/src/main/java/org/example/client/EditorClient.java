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
import java.awt.*;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;

public class EditorClient {
    private JFrame frame;
    private JTextArea textArea;
    private StompSession stompSession;
    private String clientId;
    private int cursorPosition;
    private boolean isProcessingRemoteOperation;

    public EditorClient() {
        this.clientId = UUID.randomUUID().toString();
        this.cursorPosition = 0;
        this.isProcessingRemoteOperation = false;
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

        textArea.addKeyListener(new KeyAdapter() {
            @Override
            public void keyTyped(KeyEvent e) {
                if (!isProcessingRemoteOperation) {
                    handleLocalEdit(e.getKeyChar());
                }
            }

            @Override
            public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_BACK_SPACE && !isProcessingRemoteOperation) {
                    handleLocalDelete();
                }
            }
        });

        JScrollPane scrollPane = new JScrollPane(textArea);
        frame.add(scrollPane, BorderLayout.CENTER);
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
                        System.out.println("⇦ [topic/editor] Received: " + msg);
                        handleRemoteMessage(msg);
                    }
                });

                EditorMessage syncRequest = new EditorMessage();
                syncRequest.setType(EditorMessage.MessageType.SYNC_REQUEST);
                syncRequest.setClientId(clientId); // send client ID for tracking if needed
                session.send("/app/editor/operation", syncRequest);
            }

            @Override
            public void handleTransportError(StompSession session, Throwable exception) {
                System.err.println("❌ Transport error: " + exception.getMessage());
                exception.printStackTrace();
            }
        });
    }

    private void handleLocalEdit(char c) {
        String content = String.valueOf(c);
        List<String> path = calculatePathForPosition(cursorPosition);

        EditorMessage message = new EditorMessage();
        message.setType(EditorMessage.MessageType.OPERATION);
        message.setClientId(clientId);
        message.setOperation(new Operation(Operation.Type.INSERT, content, path, System.currentTimeMillis(), clientId));
        stompSession.send("/app/editor/operation", message);

        cursorPosition++;
    }

    private void handleLocalDelete() {
        if (cursorPosition == 0) return;

        List<String> path = calculatePathForPosition(cursorPosition - 1);
        String contentToDelete = textArea.getText().substring(cursorPosition - 1, cursorPosition);

        EditorMessage message = new EditorMessage();
        message.setType(EditorMessage.MessageType.OPERATION);
        message.setClientId(clientId);
        message.setOperation(new Operation(Operation.Type.DELETE, contentToDelete, path, System.currentTimeMillis(), clientId));
        stompSession.send("/app/editor/operation", message);

        cursorPosition--;
    }

    // Only the changes shown below
private void handleRemoteMessage(EditorMessage message) {
    SwingUtilities.invokeLater(() -> {
        if (message.getType() == EditorMessage.MessageType.OPERATION &&
            !message.getOperation().getClientId().equals(clientId)) {  // ⬅️ Ignore own ops
            isProcessingRemoteOperation = true;

            Operation op = message.getOperation();
            int pos = calculatePositionFromPath(op.getPath());

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
                    }
                }
            }
            isProcessingRemoteOperation = false;
        } else if (message.getType() == EditorMessage.MessageType.SYNC_RESPONSE) {
            textArea.setText(message.getContent());
            textArea.setCaretPosition(cursorPosition);
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
            path.add("before-" + (int) document.charAt(position));
        }

        return path;
    }

    private int calculatePositionFromPath(List<String> path) {
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

        return document.length();
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(EditorClient::new);
    }
}