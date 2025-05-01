package org.example.controller;

import org.example.model.EditorMessage;
import org.example.service.CollaborationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;

@Controller
public class EditorWebSocketController {

    private final SimpMessagingTemplate messagingTemplate;
    private final CollaborationService collaborationService;

    @Autowired
    public EditorWebSocketController(SimpMessagingTemplate messagingTemplate,
                                     CollaborationService collaborationService) {
        this.messagingTemplate = messagingTemplate;
        this.collaborationService = collaborationService;
    }

    @MessageMapping("/editor/{docId}/operation")
    public void handleOperation(@Payload EditorMessage message,
                                @DestinationVariable String docId) {
        collaborationService.handleRemoteOperation(message);
        messagingTemplate.convertAndSend("/topic/document/" + docId + "/operations", message);
    }

    @MessageMapping("/editor/{docId}/join")
    public void handleUserJoin(@Payload EditorMessage message,
                               @DestinationVariable String docId) {
        collaborationService.joinDocument(docId, message.getSender());
        broadcastUserList(docId);
    }

    private void broadcastUserList(String docId) {
        EditorMessage message = new EditorMessage();
        message.setType(EditorMessage.MessageType.USER_LIST);
        message.setContent(collaborationService.getConnectedUsers(docId));
        message.setDocumentId(docId);
        messagingTemplate.convertAndSend("/topic/document/" + docId + "/users", message);
    }
}