package org.example.client;

import javafx.beans.property.StringProperty;
import org.example.crdt.Operation;
import org.example.model.EditorMessage;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.simp.stomp.*;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import org.springframework.web.socket.sockjs.client.SockJsClient;
import org.springframework.web.socket.sockjs.client.Transport;
import org.springframework.web.socket.sockjs.client.WebSocketTransport;
import javafx.application.Platform;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

public class NetworkService {
    private StompSession stompSession;
    private final EditorController controller;
    private final DocumentState documentState;

    public NetworkService(EditorController controller) {
        this.controller = controller;
        this.documentState = controller.getDocumentState();
        Platform.runLater(() -> documentState.getConnectionStatus().set("Connecting..."));
    }

    public void connectToWebSocket() {
        Platform.runLater(() -> documentState.getConnectionStatus().set("Connecting..."));

        List<Transport> transports = new ArrayList<>();
        transports.add(new WebSocketTransport(new StandardWebSocketClient()));
        WebSocketStompClient stompClient = new WebSocketStompClient(new SockJsClient(transports));
        stompClient.setMessageConverter(new MappingJackson2MessageConverter());

        stompClient.connect("ws://localhost:8080/ws", new StompSessionHandlerAdapter() {
            @Override
            public void afterConnected(StompSession session, StompHeaders connectedHeaders) {
                stompSession = session;
                Platform.runLater(() ->
                        documentState.getConnectionStatus().set("Connected to document: " + documentState.getDocumentId())
                );

                String topic = "/topic/editor/" + documentState.getDocumentId();
                session.subscribe(topic, new StompFrameHandler() {
                    @Override
                    public Type getPayloadType(StompHeaders headers) {
                        return EditorMessage.class;
                    }

                    @Override
                    public void handleFrame(StompHeaders headers, Object payload) {
                        EditorMessage msg = (EditorMessage) payload;
                        Platform.runLater(() -> controller.handleRemoteMessage(msg));
                    }
                });

                requestSync();
            }

            @Override
            public void handleTransportError(StompSession session, Throwable exception) {
                Platform.runLater(() ->
                        documentState.getConnectionStatus().set("Connection error: " + exception.getMessage())
                );
            }

            @Override
            public void handleException(StompSession session, StompCommand command, StompHeaders headers,
                                        byte[] payload, Throwable exception) {
                Platform.runLater(() ->
                        documentState.getConnectionStatus().set("Connection exception: " + exception.getMessage())
                );
            }

            public void handleDisconnect(StompSession session, StompHeaders headers) {
                Platform.runLater(() ->
                        documentState.getConnectionStatus().set("Disconnected")
                );
            }
        });
    }

    public void connectToDocument(String docId) {
        Platform.runLater(() -> documentState.getConnectionStatus().set("Reconnecting..."));

        if (stompSession != null && stompSession.isConnected()) {
            cleanup();
        }

        documentState.setDocumentId(docId);
        connectToWebSocket();
    }

    public void sendOperation(Operation operation) {
        if (stompSession == null || !stompSession.isConnected()) {
            Platform.runLater(() -> documentState.getConnectionStatus().set("Not connected - trying to reconnect..."));
            connectToWebSocket();
            documentState.getOperationQueue().add(operation);
            return;
        }

        EditorMessage message = new EditorMessage();
        message.setType(EditorMessage.MessageType.OPERATION);
        message.setClientId(documentState.getClientId());
        message.setDocumentId(documentState.getDocumentId());
        message.setOperation(operation);

        stompSession.send("/app/editor/operation", message);
    }

    public void requestSync() {
        if (stompSession != null && stompSession.isConnected()) {
            EditorMessage syncRequest = new EditorMessage();
            syncRequest.setType(EditorMessage.MessageType.SYNC_REQUEST);
            syncRequest.setClientId(documentState.getClientId());
            syncRequest.setDocumentId(documentState.getDocumentId());
            stompSession.send("/app/editor/operation", syncRequest);
        } else {
            Platform.runLater(() -> documentState.getConnectionStatus().set("Cannot sync - not connected"));
        }
    }

    public void cleanup() {
        if (stompSession != null && stompSession.isConnected()) {
            try {
                stompSession.disconnect();
            } catch (Exception e) {
                Platform.runLater(() ->
                        documentState.getConnectionStatus().set("Error during disconnect: " + e.getMessage())
                );
            }
        }
    }

    public StringProperty getConnectionStatus() {
        return documentState.getConnectionStatus();
    }
}