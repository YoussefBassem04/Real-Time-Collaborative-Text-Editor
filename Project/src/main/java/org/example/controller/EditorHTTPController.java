package org.example.controller;

import org.example.service.CollaborationService;
import org.json.JSONException;
import org.json.JSONObject;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;


@RestController
public class EditorHTTPController {
    public final CollaborationService collaborationService;
    public EditorHTTPController(CollaborationService collaborationService) {
        this.collaborationService = collaborationService;
    }

    @PostMapping("room/create")
    public JSONObject createNewRoom() throws JSONException {
        return collaborationService.createNewRoom();
    }

    @GetMapping("room/{roomId}/join")
    public JSONObject joinRoom(@PathVariable String roomId) throws JSONException {
        return collaborationService.joinRoom(roomId);
    }
}
