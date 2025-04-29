package org.client.ui;

import javafx.application.Application;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.*;

import java.io.*;

public class CollaborativeEditorUI extends Application {

    private Stage primaryStage;
    private Scene homeScene, editorScene;
    private TextArea editorArea;
    private ListView<String> userListView;
    private TextField roomCodeEditField, roomCodeViewField;

    @Override
    public void start(Stage stage) {
        this.primaryStage = stage;

        // Initialize both screens
        homeScene = createHomeScene();
        editorScene = createEditorScene();

        stage.setTitle("Collaborative Text Editor");
        stage.setScene(homeScene);
        stage.show();
    }

    private Scene createHomeScene() {
        Button createRoomBtn = new Button("Create Room");
        Button joinRoomBtn = new Button("Join Room");
        Button uploadFileBtn = new Button("Upload Text File");

        TextField joinRoomField = new TextField();
        joinRoomField.setPromptText("Enter Room Code");

        createRoomBtn.setOnAction(e -> {
            // TODO: connect to server to create a room
            primaryStage.setScene(editorScene);
        });

        joinRoomBtn.setOnAction(e -> {
            String roomCode = joinRoomField.getText();
            if (!roomCode.isEmpty()) {
                // TODO: join room via WebSocket
                primaryStage.setScene(editorScene);
            }
        });

        uploadFileBtn.setOnAction(e -> {
            FileChooser fileChooser = new FileChooser();
            fileChooser.setTitle("Open Text File");
            fileChooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Text Files", "*.txt"));
            File file = fileChooser.showOpenDialog(primaryStage);
            if (file != null) {
                // TODO: upload content and create room
                primaryStage.setScene(editorScene);
            }
        });

        VBox layout = new VBox(10, createRoomBtn, joinRoomField, joinRoomBtn, uploadFileBtn);
        layout.setPadding(new Insets(20));
        return new Scene(layout, 400, 250);
    }

    private Scene createEditorScene() {
        editorArea = new TextArea();
        editorArea.setWrapText(true);

        userListView = new ListView<>();
        userListView.setPrefWidth(150);

        roomCodeEditField = new TextField("Editable Room Code");
        roomCodeViewField = new TextField("View-Only Code");
        roomCodeEditField.setEditable(false);
        roomCodeViewField.setEditable(false);

        Button exportBtn = new Button("Export to File");
        exportBtn.setOnAction(e -> exportToFile());

        VBox rightSidebar = new VBox(new Label("Users in Room:"), userListView);
        rightSidebar.setPrefWidth(160);
        rightSidebar.setSpacing(10);
        rightSidebar.setPadding(new Insets(10));

        HBox topBar = new HBox(20);

        VBox editorBox = new VBox(new Label("Editor Code:"), roomCodeEditField);
        VBox visitorBox = new VBox(new Label("Visitor Code:"), roomCodeViewField);

        topBar.getChildren().addAll(editorBox, visitorBox);
        topBar.setPadding(new Insets(10));


        BorderPane root = new BorderPane();
        root.setTop(topBar);
        root.setCenter(editorArea);
        root.setRight(rightSidebar);
        root.setBottom(exportBtn);
        BorderPane.setMargin(exportBtn, new Insets(10));

        return new Scene(root, 800, 600);
    }

    private void exportToFile() {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Save File As");
        fileChooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Text Files", "*.txt"));
        File file = fileChooser.showSaveDialog(primaryStage);
        if (file != null) {
            try (BufferedWriter writer = new BufferedWriter(new FileWriter(file))) {
                writer.write(editorArea.getText());
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}

