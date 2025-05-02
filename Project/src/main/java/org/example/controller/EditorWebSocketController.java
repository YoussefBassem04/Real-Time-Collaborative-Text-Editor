package org.example.controller;

import org.example.model.EditorMessage;
import org.example.service.CollaborationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.SendTo;
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
                        " content: " + message.getContent()));

        switch (message.getType()) {
            case OPERATION:
                EditorMessage processedMessage = collaborationService.processMessage(message);
                if (processedMessage != null) {
                    messagingTemplate.convertAndSend("/topic/editor", processedMessage);
                }
                break;

            case SYNC_REQUEST:
                EditorMessage syncResponse = collaborationService.processMessage(message);
                messagingTemplate.convertAndSend("/topic/editor", syncResponse); // optionally broadcast to all
                break;

            default:
                break;
        }
    }
}