package org.example.client;

import javafx.application.Platform;
import javafx.scene.layout.*;
import javafx.scene.control.*;
import javafx.stage.Stage;
import javafx.geometry.*;
import javafx.beans.property.StringProperty;
import javafx.collections.FXCollections;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;
import javafx.collections.ObservableList;
import javafx.collections.ListChangeListener;

public class EditorUI {
    private BorderPane root;
    private TextArea textArea;
    private ComboBox<String> documentSelector;
    private Label statusLabel;
    private EditorController controller;
    private ListView<String> connectedUsersList;

    public EditorUI(Stage primaryStage, EditorController controller) {
        this.controller = controller;
        createUI();
    }

    private void createUI() {
        root = new BorderPane();
        root.setPadding(new Insets(10));

        HBox topBar = createTopBar();
        BorderPane textAreaWithLineNumbers = createTextAreaWithLineNumbers();
        HBox statusBar = createStatusBar();
        VBox usersBar = createUsersBar();

        root.setTop(topBar);
        root.setCenter(textAreaWithLineNumbers);
        root.setBottom(statusBar);
        root.setRight(usersBar);
    }

    private HBox createTopBar() {
        HBox topBar = new HBox(10);
        topBar.setAlignment(Pos.CENTER_LEFT);
        topBar.setPadding(new Insets(5));

        Label docLabel = new Label("Document:");
        documentSelector = new ComboBox<>();
        documentSelector.setEditable(true);
        documentSelector.setPrefWidth(200);
        documentSelector.setItems(FXCollections.observableArrayList("default"));
        documentSelector.setValue("default");

        Button connectButton = new Button("Connect");
        connectButton.setOnAction(e -> controller.connectToDocument(documentSelector.getValue()));

        Label clientIdLabel = new Label("Client ID:");
        TextField clientIdField = new TextField(controller.getDocumentState().getClientId());
        clientIdField.setPrefWidth(220);
        clientIdField.setEditable(false);

        topBar.getChildren().addAll(docLabel, documentSelector, connectButton,
                new Separator(javafx.geometry.Orientation.VERTICAL),
                clientIdLabel, clientIdField);

        return topBar;
    }

    private BorderPane createTextAreaWithLineNumbers() {
        textArea = new TextArea();
        textArea.setWrapText(true);
        textArea.textProperty().addListener((obs, oldText, newText) -> {
            if (!controller.getDocumentState().isProcessingRemoteOperation() &&
                    !controller.getUndoRedoService().isUndoRedoOperation()) {
                controller.handleLocalChange(oldText, newText);
            }
        });
        controller.setupKeyHandling(textArea);

        TextFlow lineNumbers = new TextFlow();
        lineNumbers.setPrefWidth(40);
        lineNumbers.setStyle("-fx-background-color: #f4f4f4; -fx-padding: 5px;");
        lineNumbers.setLineSpacing(0);

        textArea.textProperty().addListener((obs, oldText, newText) -> {
            updateLineNumbers(lineNumbers);
        });
        textArea.scrollTopProperty().addListener((obs, oldValue, newValue) -> {
            lineNumbers.setTranslateY(- (double)newValue);
        });

        updateLineNumbers(lineNumbers);

        BorderPane container = new BorderPane();
        container.setLeft(lineNumbers);
        container.setCenter(textArea);
        return container;
    }

    private void updateLineNumbers(TextFlow lineNumbers) {
        lineNumbers.getChildren().clear();
        String text = textArea.getText();
        int lineCount = text.isEmpty() ? 1 : text.split("\n", -1).length;
        for (int i = 1; i <= lineCount; i++) {
            Text lineNumber = new Text(i + "\n");
            lineNumber.setStyle("-fx-font-family: monospace; -fx-font-size: 14;");
            lineNumbers.getChildren().add(lineNumber);
        }
    }

    private VBox createUsersBar() {
        VBox usersBar = new VBox(10);
        usersBar.setPadding(new Insets(5));
        usersBar.setStyle("-fx-background-color: #f4f4f4; -fx-border-color: #d3d3d3; -fx-border-width: 1px;");
        usersBar.setPrefWidth(150);

        Label usersLabel = new Label("Connected Users");
        usersLabel.setStyle("-fx-font-weight: bold;");

        connectedUsersList = new ListView<>();
        connectedUsersList.setPrefHeight(400);
        connectedUsersList.setItems(controller.getDocumentState().getConnectedUsers());

        controller.getDocumentState().getConnectedUsers().addListener((ListChangeListener<String>) c -> {
            Platform.runLater(() -> connectedUsersList.setItems(controller.getDocumentState().getConnectedUsers()));
        });

        usersBar.getChildren().addAll(usersLabel, connectedUsersList);
        return usersBar;
    }

    private HBox createStatusBar() {
        HBox statusBar = new HBox(10);
        statusBar.setAlignment(Pos.CENTER_LEFT);
        statusBar.setPadding(new Insets(5));

        statusLabel = new Label();
        statusLabel.textProperty().bind(controller.getNetworkService().getConnectionStatus());

        Label charCountLabel = new Label();
        textArea.textProperty().addListener((obs, old, newText) ->
                charCountLabel.setText("Characters: " + newText.length()));
        charCountLabel.setText("Characters: 0");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Button undoButton = new Button("Undo");
        undoButton.setOnAction(e -> controller.performUndo());
        undoButton.disableProperty().bind(textArea.disabledProperty());

        Button redoButton = new Button("Redo");
        redoButton.setOnAction(e -> controller.performRedo());
        redoButton.disableProperty().bind(textArea.disabledProperty());

        statusBar.getChildren().addAll(statusLabel, spacer, charCountLabel,
                new Separator(javafx.geometry.Orientation.VERTICAL),
                undoButton, redoButton);

        return statusBar;
    }

    public BorderPane getRoot() {
        return root;
    }

    public TextArea getTextArea() {
        return textArea;
    }

    public ComboBox<String> getDocumentSelector() {
        return documentSelector;
    }

    public Label getStatusLabel() {
        return statusLabel;
    }
}