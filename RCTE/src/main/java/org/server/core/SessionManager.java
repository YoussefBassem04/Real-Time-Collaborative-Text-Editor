package org.server.core;



import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class SessionManager {

    // Maps roomCode -> set of sessionIds
    private final Map<String, Set<String>> roomSessions = new ConcurrentHashMap<>();

    // Maps sessionId -> roomCode
    private final Map<String, String> sessionToRoom = new ConcurrentHashMap<>();

    public void joinRoom(String sessionId, String roomCode) {
        roomSessions.computeIfAbsent(roomCode, k -> ConcurrentHashMap.newKeySet()).add(sessionId);
        sessionToRoom.put(sessionId, roomCode);
    }

    public void leaveRoom(String sessionId) {
        String roomCode = sessionToRoom.remove(sessionId);
        if (roomCode != null) {
            Set<String> sessions = roomSessions.get(roomCode);
            if (sessions != null) {
                sessions.remove(sessionId);
                if (sessions.isEmpty()) {
                    roomSessions.remove(roomCode);
                }
            }
        }
    }

    public Set<String> getSessionsInRoom(String roomCode) {
        return roomSessions.getOrDefault(roomCode, Collections.emptySet());
    }

    public Optional<String> getRoomOfSession(String sessionId) {
        return Optional.ofNullable(sessionToRoom.get(sessionId));
    }

    public boolean isRoomEmpty(String roomCode) {
        return !roomSessions.containsKey(roomCode) || roomSessions.get(roomCode).isEmpty();
    }
}

