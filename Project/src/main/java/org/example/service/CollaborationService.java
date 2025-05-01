package org.example.service;

import org.example.crdt.Document;
import org.example.crdt.Operation;
import org.example.model.EditorMessage;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class CollaborationService {
    private final Map<String, Document> documents = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> documentUsers = new ConcurrentHashMap<>();
    private int nextSiteId = 1;

    public void handleRemoteOperation(EditorMessage message) {
        if (message == null || message.getDocumentId() == null || message.getOperation() == null) {
            return;
        }

        Document doc = documents.computeIfAbsent(message.getDocumentId(),
                k -> new Document(nextSiteId++));
        doc.applyOperation(message.getOperation());
    }

    public void joinDocument(String docId, String username) {
        documentUsers.computeIfAbsent(docId, k -> ConcurrentHashMap.newKeySet())
                .add(username);
    }

    public void leaveDocument(String docId, String username) {
        Set<String> users = documentUsers.get(docId);
        if (users != null) {
            users.remove(username);
        }
    }

    public List<String> getConnectedUsers(String docId) {
        Set<String> users = documentUsers.get(docId);
        return users != null ? new ArrayList<>(users) : Collections.emptyList();
    }

    public Operation applyLocalInsert(String docId, int position, char value) {
        Document doc = documents.computeIfAbsent(docId, k -> new Document(nextSiteId++));
        return doc.localInsert(position, value);
    }

    public Operation applyLocalDelete(String docId, int position) {
        Document doc = documents.get(docId);
        return doc != null ? doc.localDelete(position) : null;
    }

    public String getDocumentText(String docId) {
        Document doc = documents.get(docId);
        return doc != null ? doc.getText() : "";
    }
}