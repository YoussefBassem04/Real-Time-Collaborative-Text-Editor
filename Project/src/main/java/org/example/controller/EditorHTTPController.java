package org.example.controller;

import java.util.Map;

import org.example.service.CollaborationService;
import org.json.JSONException;
import org.json.JSONObject;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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

    @PostMapping(value = "/room/create", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> createNewRoom() {
        Map<String, Object> response = collaborationService.createNewRoom();
        return ResponseEntity.ok(response);
    }

    @GetMapping(value = "/room/{roomId}/join", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> joinRoom(@PathVariable String roomId) {
        Map<String, Object> response = collaborationService.joinRoom(roomId);
        return ResponseEntity.ok(response);
    }
}
