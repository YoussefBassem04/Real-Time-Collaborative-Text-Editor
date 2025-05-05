package org.example.client;

import javafx.application.Application;
import javafx.scene.Scene;
import javafx.stage.Stage;

public class EditorClient extends Application {
    private WelcomeUI welcomeScene;
    private EditorController controller;

    @Override
    public void start(Stage primaryStage) {
        controller = new EditorController();
        welcomeScene = new WelcomeUI(primaryStage, controller);

        Scene scene = welcomeScene.getScene();
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