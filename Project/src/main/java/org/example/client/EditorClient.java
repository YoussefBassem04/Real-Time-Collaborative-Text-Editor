package org.example.client;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.TextArea;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import org.example.crdt.Operation;
import org.example.crdt.TreeBasedCRDT;
import org.example.model.EditorMessage;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.simp.stomp.*;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

import java.lang.reflect.Type;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public class EditorClient extends Application {
    private TextArea textArea;
    private StompSession stompSession;
    private final String documentId = "default-doc";
    private final String username = "user-" + (int)(Math.random() * 1000);
    private final AtomicBoolean isProcessingRemoteUpdate = new AtomicBoolean(false);

    private final TreeBasedCRDT localDoc = new TreeBasedCRDT();

    @Override
    public void start(Stage primaryStage) {
        initializeUI(primaryStage);
        connectToWebSocketServer();
    }

    private void initializeUI(Stage stage) {
        textArea = new TextArea();
        textArea.setWrapText(true);

        textArea.textProperty().addListener((obs, oldText, newText) -> {
            if (!isProcessingRemoteUpdate.get()) {
                handleLocalTextChange(oldText, newText);
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
            public void afterConnected(StompSession session, StompHeaders headers) {
                stompSession = session;

                // Subscriptions
                session.subscribe("/topic/document/" + documentId + "/operations", this);
                session.subscribe("/topic/document/" + documentId + "/users", this);

                // Join document
                EditorMessage joinMessage = new EditorMessage();
                joinMessage.setType(EditorMessage.MessageType.USER_JOIN);
                joinMessage.setSender(username);
                joinMessage.setDocumentId(documentId);
                session.send("/app/editor/" + documentId + "/join", joinMessage);
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                if (payload instanceof EditorMessage msg) {
                    Platform.runLater(() -> handleIncomingMessage(msg));
                }
            }

            @Override
            public Type getPayloadType(StompHeaders headers) {
                return EditorMessage.class;
            }
        });
    }

    private void handleLocalTextChange(String oldText, String newText) {
        if (newText.length() > oldText.length()) {
            // Insertion
            int index = findDiffIndex(oldText, newText);
            char inserted = newText.charAt(index);
            sendInsertOperation(index, inserted);
        } else if (newText.length() < oldText.length()) {
            // Deletion
            int index = findDiffIndex(newText, oldText);
            sendDeleteOperation(index);
        }
    }

    private int findDiffIndex(String shorter, String longer) {
        int index = 0;
        while (index < shorter.length() && shorter.charAt(index) == longer.charAt(index)) {
            index++;
        }
        return index;
    }

    private void sendInsertOperation(int position, char value) {
        if (stompSession != null && stompSession.isConnected()) {
            // Always attach to root for simplicity
            Operation op = localDoc.insert(username, value, "root");

            EditorMessage message = new EditorMessage();
            message.setType(EditorMessage.MessageType.OPERATION);
            message.setSender(username);
            message.setDocumentId(documentId);
            message.setOperation(op);

            stompSession.send("/app/editor/" + documentId + "/operation", message);
        }
    }

    private void sendDeleteOperation(int index) {
        if (stompSession != null && stompSession.isConnected()) {
            List<String> ids = localDoc.getNodeIds();
            if (index >= 0 && index < ids.size()) {
                String targetId = ids.get(index);
                Operation op = localDoc.delete(targetId);
                if (op != null) {
                    EditorMessage message = new EditorMessage();
                    message.setType(EditorMessage.MessageType.OPERATION);
                    message.setSender(username);
                    message.setDocumentId(documentId);
                    message.setOperation(op);

                    stompSession.send("/app/editor/" + documentId + "/operation", message);
                }
            }
        }
    }

    private void handleIncomingMessage(EditorMessage message) {
        switch (message.getType()) {
            case OPERATION:
                isProcessingRemoteUpdate.set(true);
                localDoc.apply(message.getOperation());
                textArea.setText(localDoc.getText());
                textArea.positionCaret(localDoc.getText().length());
                isProcessingRemoteUpdate.set(false);
                break;
            case USER_LIST:
                System.out.println("Users in room: " + message.getContent());
                break;
            default:
                System.out.println("Unhandled message type: " + message.getType());
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}
