package org.example.service;

import org.example.crdt.Operation;
import org.example.crdt.TreeBasedCRDT;
import org.example.model.EditorMessage;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class CollaborationService {
    private final Map<String, TreeBasedCRDT> documents = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> users = new ConcurrentHashMap<>();

    public void handleRemoteOperation(EditorMessage msg) {
        TreeBasedCRDT doc = documents.computeIfAbsent(msg.getDocumentId(), k -> new TreeBasedCRDT());
        doc.apply(msg.getOperation());
    }

    public void joinDocument(String docId, String username) {
        users.computeIfAbsent(docId, k -> ConcurrentHashMap.newKeySet()).add(username);
    }

    public List<String> getConnectedUsers(String docId) {
        return new ArrayList<>(users.getOrDefault(docId, Collections.emptySet()));
    }

    public String getDocumentText(String docId) {
        return documents.getOrDefault(docId, new TreeBasedCRDT()).getText();
    }
}
