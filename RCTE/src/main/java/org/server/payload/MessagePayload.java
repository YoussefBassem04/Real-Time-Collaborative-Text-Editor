package org.server.payload;



public class MessagePayload {
    private String roomCode;
    private String action; // INSERT, DELETE, UNDO, REDO
    private String data;   // Character or NodeId

    // Getters and Setters
    public String getRoomCode() { return roomCode; }
    public void setRoomCode(String roomCode) { this.roomCode = roomCode; }

    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }

    public String getData() { return data; }
    public void setData(String data) { this.data = data; }
}
