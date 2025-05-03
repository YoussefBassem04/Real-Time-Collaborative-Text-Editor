package org.example.controller;

import org.example.model.EditorMessage;
import org.example.service.CollaborationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;
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
    public void handleOperation(EditorMessage message) {
        System.out.println("▶ Received: " + message.getType() +
                (message.getOperation() != null ?
                        " " + message.getOperation().getType() + " '" + message.getOperation().getContent() + "'" :
                        " content: " + message.getContent()) +
                " for document: " + message.getDocumentId());

        EditorMessage processedMessage = collaborationService.processMessage(message);
        if (processedMessage != null) {
            String documentId = processedMessage.getDocumentId();
            if (documentId == null || documentId.isEmpty()) {
                documentId = "default";
            }

            // Send to document-specific topic
            String destination = "/topic/editor/" + documentId;
            messagingTemplate.convertAndSend(destination, processedMessage);
            System.out.println("◀ Sent to: " + destination);
        }
    }

    @EventListener
    public void handleSessionDisconnect(SessionDisconnectEvent event) {
        SimpMessageHeaderAccessor headers = SimpMessageHeaderAccessor.wrap(event.getMessage());
        String clientId = headers.getSessionId();
        collaborationService.handleClientDisconnect(clientId);
    }
}