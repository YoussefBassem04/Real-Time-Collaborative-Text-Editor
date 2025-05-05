package org.example.client;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * UI for handling room creation and joining functionality before launching the editor
 */
public class WelcomeUI {
    private final Stage primaryStage;
    private final EditorController controller;
    private TextField roomIdField;
    private Scene welcomeScene;
    
    public WelcomeUI(Stage primaryStage, EditorController controller) {
        this.primaryStage = primaryStage;
        this.controller = controller;
        createUI();
    }
    
    private void createUI() {
        BorderPane root = new BorderPane();
        root.setPadding(new Insets(20));
        
        // Title
        Label titleLabel = new Label("Collaborative Editor");
        titleLabel.setStyle("-fx-font-size: 24px; -fx-font-weight: bold;");
        
        // Create Room Button
        Button createRoomButton = new Button("Create New Room");
        createRoomButton.setPrefWidth(200);
        createRoomButton.setOnAction(e -> {
            try {
                createNewRoom();
            } catch (Exception e1) {
                e1.printStackTrace();
            }
        });
        
        // Room ID input
        Label roomIdLabel = new Label("Room ID:");
        roomIdField = new TextField();
        roomIdField.setPromptText("Enter room ID to join");
        roomIdField.setPrefWidth(300);
        
        HBox roomIdBox = new HBox(10);
        roomIdBox.setAlignment(Pos.CENTER);
        roomIdBox.getChildren().addAll(roomIdLabel, roomIdField);
        
        // Join Room Button
        Button joinRoomButton = new Button("Join Room");
        joinRoomButton.setPrefWidth(200);
        joinRoomButton.setOnAction(e -> {
            try {
                joinRoom(roomIdField.getText());
            } catch (Exception e1) {
                e1.printStackTrace();
            }
        });
        
        // Container for all controls
        VBox controls = new VBox(20);
        controls.setAlignment(Pos.CENTER);
        controls.getChildren().addAll(titleLabel, createRoomButton, new Label("- OR -"), roomIdBox, joinRoomButton);
        
        root.setCenter(controls);
        
        welcomeScene = new Scene(root, 600, 400);
    }
    
     private void createNewRoom() throws Exception {
        try {
            JSONObject response = controller.createNewRoom();
            
            if (response.has("editRoomId") && response.has("readOnlyRoomId")) {
                String editRoomId = response.getString("editRoomId");
                String readOnlyRoomId = response.getString("readOnlyRoomId");
                
                roomIdField.setText(editRoomId);
                
                controller.setRoomInfo(editRoomId, readOnlyRoomId, true);
                launchEditor();
            } else {
                showError("Failed to create room: Invalid response format");
            }
        } catch (JSONException e) {
            showError("Error creating room: " + e.getMessage());
        }
    }

    private void joinRoom(String roomId) throws Exception{
        if (roomId == null || roomId.trim().isEmpty()) {
            showError("Please enter a valid Room ID");
            return;
        }
        
        try {
            JSONObject response = controller.joinRoom(roomId);
            
            if (response.has("editRoomId") && response.has("readOnlyRoomId")) {
                String editRoomId = response.getString("editRoomId");
                String readOnlyRoomId = response.getString("readOnlyRoomId");
                boolean canEdit = response.has("canEdit") && response.getBoolean("canEdit");
                
                // Set room info in controller and switch to editor
                controller.setRoomInfo(editRoomId, readOnlyRoomId, canEdit);
                launchEditor();
            } else {
                String errorMsg = response.has("message") ? 
                    response.getString("message") : "Failed to join room";
                showError(errorMsg);
            }
        } catch (JSONException e) {
            showError("Error joining room: " + e.getMessage());
        }
    }
    
    /**
     * Creates and switches to the editor UI
     */
    private void launchEditor() {
        // Create editor UI
        EditorUI editorUI = new EditorUI(primaryStage, controller);
        
        // Set the scene
        Scene editorScene = new Scene(editorUI.getRoot(), 900, 600);
        primaryStage.setScene(editorScene);
        
        // Set title based on edit permissions
        String mode = controller.canEdit() ? "Edit" : "Read-Only";
        primaryStage.setTitle("Collaborative Editor - " + mode + " Mode");
        
        // Initialize editor with the active room ID
        controller.connectToDocument(controller.getActiveRoomId());
    }
    
    private void showError(String message) {
        // Simple error handling - in a real app you might use an alert dialog
        System.err.println(message);
    }
    
    public Scene getScene() {
        return welcomeScene;
    }
}