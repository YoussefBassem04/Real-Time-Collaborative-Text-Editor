package org.example.client;

import javafx.application.Platform;
import javafx.scene.layout.*;
import javafx.scene.control.*;
import javafx.stage.Stage;
import javafx.stage.FileChooser;
import javafx.geometry.*;
import javafx.beans.property.StringProperty;
import javafx.collections.FXCollections;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;
import javafx.collections.ObservableList;
import javafx.collections.ListChangeListener;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class EditorUI {
    private BorderPane root;
    private TextArea textArea;
    private ComboBox<String> documentSelector;
    private Label statusLabel;
    private EditorController controller;
    private ListView<String> connectedUsersList;
    private Stage primaryStage;

    public EditorUI(Stage primaryStage, EditorController controller) {
        this.controller = controller;
        this.primaryStage = primaryStage;
        // Set UI reference in controller for bidirectional access
        controller.setUI(this);
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

        Label editLabel = new Label("Edit Code:");
        TextField editlabelid = new TextField(controller.getEditRoomId());
        editlabelid.setEditable(false);

        Label readLabel = new Label("Read-Only Code:");
        TextField readlabelid = new TextField(controller.getReadOnlyRoomId());
        readlabelid.setEditable(false);
        Label clientIdLabel = new Label("Client ID:");
        TextField clientIdField = new TextField(controller.getDocumentState().getClientId());
        clientIdField.setPrefWidth(220);
        clientIdField.setEditable(false);

        Button importButton = createImportButton();
        Button exportButton = createExportButton();

        Separator separator1 = new Separator(javafx.geometry.Orientation.VERTICAL);
        Separator separator2 = new Separator(javafx.geometry.Orientation.VERTICAL);

        topBar.getChildren().addAll(editLabel, editlabelid, readLabel, readlabelid,
                separator1, clientIdLabel, clientIdField,
                separator2, importButton, exportButton);

        return topBar;
    }

    private Button createImportButton() {
        Button importButton = new Button("Import");
        importButton.setDisable(!controller.canEdit());
        importButton.setOnAction(event -> {
            FileChooser fileChooser = new FileChooser();
            fileChooser.setTitle("Import File");
            fileChooser.getExtensionFilters().addAll(
                    new FileChooser.ExtensionFilter("Text Files", "*.txt"),
                    new FileChooser.ExtensionFilter("All Files", "*.*")
            );

            File selectedFile = fileChooser.showOpenDialog(primaryStage);
            if (selectedFile != null) {
                try {
                    String content = Files.readString(selectedFile.toPath());

                    // Only proceed if we have permission to edit
                    if (controller.canEdit()) {
                        // Properly handle the import by temporarily marking this as a remote operation
                        // This prevents duplication issues when synchronizing
                        controller.getDocumentState().setProcessingRemoteOperation(true);

                        try {
                            // Clear the existing content first
                            textArea.clear();

                            // Update the text area with imported content
                            textArea.setText(content);

                            // Update the previous content in document state to prevent duplication on sync
                            controller.getDocumentState().setPreviousContent(content);

                            // Notify the server about this complete replacement
                            // We'll use a special method to handle imports - let's add this later
                            controller.handleDocumentImport(content);

                            showAlert(Alert.AlertType.INFORMATION, "Import Successful",
                                    "Document has been imported and synchronized.");
                        } finally {
                            // Reset the processing flag
                            controller.getDocumentState().setProcessingRemoteOperation(false);
                        }
                    } else {
                        showAlert(Alert.AlertType.WARNING, "Read-Only Mode",
                                "Cannot import in read-only mode.");
                    }
                } catch (IOException e) {
                    showAlert(Alert.AlertType.ERROR, "Import Error",
                            "Failed to import file: " + e.getMessage());
                }
            }
        });
        return importButton;
    }

    private Button createExportButton() {
        Button exportButton = new Button("Export");
        exportButton.setOnAction(event -> {
            FileChooser fileChooser = new FileChooser();
            fileChooser.setTitle("Export File");
            fileChooser.getExtensionFilters().addAll(
                    new FileChooser.ExtensionFilter("Text Files", "*.txt"),
                    new FileChooser.ExtensionFilter("All Files", "*.*")
            );

            File selectedFile = fileChooser.showSaveDialog(primaryStage);
            if (selectedFile != null) {
                try {
                    String content = textArea.getText();
                    Files.writeString(selectedFile.toPath(), content);
                } catch (IOException e) {
                    showAlert("Export Error", "Failed to export file: " + e.getMessage());
                }
            }
        });
        return exportButton;
    }

    private void showAlert(Alert.AlertType alertType, String title, String message) {
        Alert alert = new Alert(alertType);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(message);
        alert.showAndWait();
    }

    private void showAlert(String title, String message) {
        // For backward compatibility, default to ERROR type
        showAlert(Alert.AlertType.ERROR, title, message);
    }

    private BorderPane createTextAreaWithLineNumbers() {
        textArea = new TextArea();
        if(controller.canEdit()) {
            textArea.setEditable(true);
        } else {
            textArea.setEditable(false);
        }
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