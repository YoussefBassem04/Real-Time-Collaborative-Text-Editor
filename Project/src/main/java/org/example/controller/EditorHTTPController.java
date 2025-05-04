package org.example.controller;

import org.example.service.CollaborationService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.databind.util.JSONPObject;

@RestController
public class EditorHTTPController {
    public final CollaborationService collaborationService;
    public EditorHTTPController(CollaborationService collaborationService) {
        this.collaborationService = collaborationService;
    }

    @PostMapping("room/create")
    public JSONPObject createNewRoom() {
        return collaborationService.createNewRoom();
    }

    @GetMapping("room/{roomId}/join")
    public JSONPObject joinRoom(@PathVariable String roomId) {
        return collaborationService.joinRoom(roomId);
    }
}
