package org.server.core;



import java.util.concurrent.ConcurrentHashMap;
import java.util.Optional;
import java.util.Map;

public class DocumentManager {
    private final Map<String, TreeBasedCRDT> documents = new ConcurrentHashMap<>();

    public TreeBasedCRDT createDocument(String roomCode) {
        TreeBasedCRDT crdt = new TreeBasedCRDT();
        documents.put(roomCode, crdt);
        return crdt;
    }

    public Optional<TreeBasedCRDT> getDocument(String roomCode) {
        return Optional.ofNullable(documents.get(roomCode));
    }
}

