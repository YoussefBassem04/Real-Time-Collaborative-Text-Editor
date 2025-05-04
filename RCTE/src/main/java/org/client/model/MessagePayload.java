package org.client.model;



public class MessagePayload {
    public String roomCode;
    public String action;   // INSERT, DELETE, UNDO, REDO
    public String data;

    public MessagePayload(String roomCode, String action, String data) {
        this.roomCode = roomCode;
        this.action = action;
        this.data = data;
    }
}
