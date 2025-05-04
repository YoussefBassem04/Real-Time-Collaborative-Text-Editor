package org.client.model;


import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

public class RoomSession {
    public String roomCodeEditor;
    public String roomCodeVisitor;
    public ObservableList<String> activeUsers = FXCollections.observableArrayList();

    public RoomSession(String roomCodeEditor, String roomCodeVisitor) {
        this.roomCodeEditor = roomCodeEditor;
        this.roomCodeVisitor = roomCodeVisitor;
    }
}

