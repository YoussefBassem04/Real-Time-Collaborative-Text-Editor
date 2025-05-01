package org.example.client;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.TextArea;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import org.example.crdt.Operation;
import org.example.model.EditorMessage;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.simp.stomp.*;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

import java.lang.reflect.Type;
import java.util.concurrent.atomic.AtomicBoolean;

public class EditorClient extends Application {
    private TextArea textArea;
    private StompSession stompSession;
    private final String documentId = "default-doc";
    private final String username = "user-" + (int)(Math.random() * 1000);
    private final AtomicBoolean isProcessingRemoteUpdate = new AtomicBoolean(false);

    @Override
    public void start(Stage primaryStage) {
        initializeUI(primaryStage);
        connectToWebSocketServer();
    }

    private void initializeUI(Stage stage) {
        textArea = new TextArea();
        textArea.textProperty().addListener((obs, oldVal, newVal) -> {
            if (!isProcessingRemoteUpdate.get()) {
                handleLocalTextChange(oldVal, newVal);
            }
        });

        VBox root = new VBox(textArea);
        Scene scene = new Scene(root, 800, 600);
        stage.setTitle("Collaborative Editor - " + username);
        stage.setScene(scene);
        stage.show();
    }

    private void connectToWebSocketServer() {
        WebSocketStompClient stompClient = new WebSocketStompClient(new StandardWebSocketClient());
        stompClient.setMessageConverter(new MappingJackson2MessageConverter());

        stompClient.connect("ws://localhost:8080/ws", new StompSessionHandlerAdapter() {
            @Override
            public void afterConnected(StompSession session, StompHeaders connectedHeaders) {
                stompSession = session;

                // Subscribe to document updates
                session.subscribe("/topic/document/" + documentId + "/operations", this);
                session.subscribe("/topic/document/" + documentId + "/users", this);

                // Join the document
                EditorMessage joinMessage = new EditorMessage();
                joinMessage.setType(EditorMessage.MessageType.USER_JOIN);
                joinMessage.setSender(username);
                joinMessage.setDocumentId(documentId);
                session.send("/app/editor/" + documentId + "/join", joinMessage);
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                EditorMessage message = (EditorMessage) payload;
                Platform.runLater(() -> handleIncomingMessage(message));
            }

            @Override
            public Type getPayloadType(StompHeaders headers) {
                return EditorMessage.class;
            }
        });
    }

    private void handleLocalTextChange(String oldText, String newText) {
        // Calculate difference between old and new text
        if (newText.length() > oldText.length()) {
            // Insertion occurred
            int pos = 0;
            while (pos < oldText.length() && oldText.charAt(pos) == newText.charAt(pos)) {
                pos++;
            }
            char insertedChar = newText.charAt(pos);
            sendInsertOperation(pos, insertedChar);
        } else if (newText.length() < oldText.length()) {
            // Deletion occurred
            int pos = 0;
            while (pos < newText.length() && oldText.charAt(pos) == newText.charAt(pos)) {
                pos++;
            }
            sendDeleteOperation(pos);
        }
    }

    private void sendInsertOperation(int position, char value) {
        if (stompSession != null && stompSession.isConnected()) {
            EditorMessage message = new EditorMessage();
            message.setType(EditorMessage.MessageType.OPERATION);
            message.setSender(username);
            message.setDocumentId(documentId);
            // Here you would create a proper Operation object using your CRDT
            stompSession.send("/app/editor/" + documentId + "/operation", message);
        }
    }

    private void sendDeleteOperation(int position) {
        if (stompSession != null && stompSession.isConnected()) {
            EditorMessage message = new EditorMessage();
            message.setType(EditorMessage.MessageType.OPERATION);
            message.setSender(username);
            message.setDocumentId(documentId);
            // Here you would create a proper Operation object using your CRDT
            stompSession.send("/app/editor/" + documentId + "/operation", message);
        }
    }

    private void handleIncomingMessage(EditorMessage message) {
        if (message == null) return;

        switch (message.getType()) {
            case OPERATION:
                isProcessingRemoteUpdate.set(true);
                // Apply remote operation to textArea
                // You would use your CRDT here to apply the operation
                isProcessingRemoteUpdate.set(false);
                break;

            case USER_LIST:
                System.out.println("Users in document: " + message.getContent());
                break;

            default:
                System.out.println("Received message of type: " + message.getType());
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}