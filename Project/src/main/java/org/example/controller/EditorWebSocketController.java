package org.example.controller;

import org.example.model.EditorMessage;
import org.example.service.CollaborationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;
import org.springframework.web.socket.messaging.SessionConnectEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

@Controller
public class EditorWebSocketController {

    private final CollaborationService collaborationService;
    private final SimpMessagingTemplate messagingTemplate;

    @Autowired
    public EditorWebSocketController(CollaborationService collaborationService,
                                     SimpMessagingTemplate messagingTemplate) {
        this.collaborationService = collaborationService;
        this.messagingTemplate = messagingTemplate;
    }

    @MessageMapping("/editor/operation")
    public void handleOperation(EditorMessage message, SimpMessageHeaderAccessor headerAccessor) {
        System.out.println("▶ Received: " + message.getType() +
                (message.getOperations() != null ?
                        " " + message.getOperations().size() + " operations" :
                        " content: " + message.getContent()));

        switch (message.getType()) {
            case CREATE_DOCUMENT:
                String documentId = collaborationService.createDocument();
                EditorMessage createResponse = new EditorMessage();
                createResponse.setType(EditorMessage.MessageType.CREATE_DOCUMENT_RESPONSE);
                createResponse.setDocumentId(documentId);
                createResponse.setClientId(message.getClientId());
                messagingTemplate.convertAndSend("/topic/editor", createResponse);
                break;

            case OPERATION:
            case SYNC_REQUEST:
            case CURSOR_UPDATE:
                EditorMessage processedMessage = collaborationService.processMessage(message);
                if (processedMessage != null) {
                    messagingTemplate.convertAndSend("/topic/editor", processedMessage);
                }
                break;

            default:
                break;
        }
    }

    @EventListener
    public void handleWebSocketConnectListener(SessionConnectEvent event) {
        SimpMessageHeaderAccessor headerAccessor = SimpMessageHeaderAccessor.wrap(event.getMessage());
        String clientId = headerAccessor.getSessionId();
        // Document ID should be provided by client; placeholder for now
        collaborationService.clientConnected(clientId, "default");
        System.out.println("Client connected: " + clientId);
    }

    @EventListener
    public void handleWebSocketDisconnectListener(SessionDisconnectEvent event) {
        SimpMessageHeaderAccessor headerAccessor = SimpMessageHeaderAccessor.wrap(event.getMessage());
        String clientId = headerAccessor.getSessionId();
        collaborationService.clientDisconnected(clientId);
        System.out.println("Client disconnected: " + clientId);
    }
}