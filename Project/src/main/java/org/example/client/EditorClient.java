package org.example.client;

import javafx.application.Application;
import javafx.scene.Scene;
import javafx.stage.Stage;

public class EditorClient extends Application {
    private EditorUI editorUI;
    private EditorController controller;

    @Override
    public void start(Stage primaryStage) {
        controller = new EditorController();
        editorUI = new EditorUI(primaryStage, controller);

        Scene scene = new Scene(editorUI.getRoot(), 900, 600);
        primaryStage.setScene(scene);
        primaryStage.setTitle("Collaborative JavaFX Editor");
        primaryStage.setOnCloseRequest(e -> controller.cleanup());
        primaryStage.show();

        controller.initialize();
    }

    public static void main(String[] args) {
        launch(args);
    }
}