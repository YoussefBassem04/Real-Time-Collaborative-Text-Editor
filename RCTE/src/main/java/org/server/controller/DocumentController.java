package org.server.controller;



import org.server.core.DocumentManager;
import org.server.core.TreeBasedCRDT;
import org.server.payload.MessagePayload;
import org.springframework.messaging.handler.annotation.*;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;

@Controller
public class DocumentController {

    private final SimpMessagingTemplate messagingTemplate;
    private final DocumentManager documentManager;

    public DocumentController(SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
        this.documentManager = new DocumentManager();
    }

    @MessageMapping("/document/edit") // Client sends to /app/document/edit
    public void handleEdit(MessagePayload payload) {
        String roomCode = payload.getRoomCode();
        String action = payload.getAction();
        String data = payload.getData();

        TreeBasedCRDT doc = documentManager.getDocument(roomCode)
                .orElseGet(() -> documentManager.createDocument(roomCode));

        if ("INSERT".equalsIgnoreCase(action)) {
            for (char c : data.toCharArray()) {
                doc.insert(c, doc.getRootId(), null);
            }
        } else if ("DELETE".equalsIgnoreCase(action)) {
            doc.delete(data);
        } else if ("UNDO".equalsIgnoreCase(action)) {
            doc.undo();
        } else if ("REDO".equalsIgnoreCase(action)) {
            doc.redo();
        }

        // Broadcast updated document
        messagingTemplate.convertAndSend("/topic/room/" + roomCode, doc.getText());
    }
}
