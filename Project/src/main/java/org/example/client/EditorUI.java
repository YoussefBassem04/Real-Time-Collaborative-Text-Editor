package org.example.client;

import javafx.scene.layout.*;
import javafx.scene.control.*;
import javafx.stage.Stage;
import javafx.geometry.*;
import javafx.beans.property.StringProperty;
import javafx.collections.FXCollections;

public class EditorUI {
    private BorderPane root;
    private TextArea textArea;
    private ComboBox<String> documentSelector;
    private Label statusLabel;
    private EditorController controller;

    public EditorUI(Stage primaryStage, EditorController controller) {
        this.controller = controller;
        createUI();
    }

    private void createUI() {
        root = new BorderPane();
        root.setPadding(new Insets(10));

        HBox topBar = createTopBar();
        textArea = createTextArea();
        HBox statusBar = createStatusBar();

        root.setTop(topBar);
        root.setCenter(textArea);
        root.setBottom(statusBar);
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

    private TextArea createTextArea() {
        TextArea area = new TextArea();
        area.setWrapText(true);
        area.textProperty().addListener((obs, oldText, newText) -> {
            if (!controller.getDocumentState().isProcessingRemoteOperation() &&
                    !controller.getUndoRedoService().isUndoRedoOperation()) {
                controller.handleLocalChange(oldText, newText);
            }
        });
        controller.setupKeyHandling(area);
        return area;
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