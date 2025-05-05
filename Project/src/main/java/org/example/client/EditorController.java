package org.example.client;

import java.util.ArrayList;
import java.util.List;

import org.example.crdt.Operation;
import org.example.model.EditorMessage;
import org.json.JSONObject;

import javafx.scene.control.TextArea;

public class EditorController {
    private final DocumentState documentState;
    private final NetworkService networkService;
    private final UndoRedoService undoRedoService;
    private final OperationService operationService;
    private String editRoomId;
    private String readOnlyRoomId;
    private Boolean canEdit;
    private EditorUI ui; // Reference to the UI

    public EditorController() {
        this.documentState = new DocumentState();
        this.networkService = new NetworkService(this);
        this.undoRedoService = new UndoRedoService(this);
        this.operationService = new OperationService(this);
    }

    public void setUI(EditorUI ui) {
        this.ui = ui;
    }

    public void initialize() {
        networkService.connectToWebSocket();
    }

    public JSONObject createNewRoom() throws Exception {
        return networkService.createNewRoom();
    }

    public JSONObject joinRoom(String roomId) throws Exception {
        return networkService.joinRoom(roomId);
    }

    public void connectToDocument(String docId) {
        documentState.setDocumentId(docId);
        networkService.connectToDocument(docId);
    }

    /**
     * Handles importing a document, which replaces the entire content.
     * This method ensures proper synchronization with the server while
     * preventing duplicate content issues during future syncs.
     *
     * @param importedContent The new content to replace the current document with
     */
    public void handleDocumentImport(String importedContent) {
        if (ui == null) {
            System.err.println("Error: UI not initialized for import operation");
            return;
        }

        if (importedContent == null) {
            System.err.println("Error: Imported content is null");
            return;
        }

        try {
            // Set a flag to prevent duplication when synchronizing
            documentState.setProcessingRemoteOperation(true);

            // First, update the UI with the imported content
            TextArea textArea = ui.getTextArea();
            textArea.setText(importedContent);

            // Clear existing undo/redo history
            undoRedoService.resetState();

            // Generate new character IDs for the imported content
            List<String> newCharIds = new ArrayList<>();
            long timestamp = System.currentTimeMillis();
            for (int i = 0; i < importedContent.length(); i++) {
                newCharIds.add(documentState.getClientId() + ":" + timestamp + ":" + i);
            }

            // Update the local character ID tracking
            documentState.getCharacterIds().clear();
            documentState.getCharacterIds().addAll(newCharIds);

            // Update document state
            documentState.setPreviousContent(importedContent);

            // Create and send a SYNC operation with the complete document content
            Operation syncOp = new Operation(
                    Operation.Type.SYNC,
                    importedContent,
                    null,
                    System.currentTimeMillis(),
                    documentState.getClientId()
            );
            syncOp.setCharacterIds(new ArrayList<>(documentState.getCharacterIds()));

            // Send the sync operation to the server
            networkService.sendOperation(syncOp);

            System.out.println("Document imported and synced: " + documentState.getDocumentId() +
                    " (" + importedContent.length() + " characters)");
        } catch (Exception e) {
            System.err.println("Error during document import: " + e.getMessage());
            e.printStackTrace();
        } finally {
            // Always reset the processing flag
            documentState.setProcessingRemoteOperation(false);
        }
    }

    public void handleLocalChange(String oldText, String newText) {
        operationService.handleLocalChange(oldText, newText);
    }

    public void handleRemoteMessage(EditorMessage message) {
        if (message.getType() == EditorMessage.MessageType.USER_LIST && message.getConnectedUsers() != null) {
            documentState.getConnectedUsers().setAll(message.getConnectedUsers());
        }
        operationService.handleRemoteMessage(message);
    }

    public void performUndo() {
        undoRedoService.performUndo();
    }

    public void performRedo() {
        undoRedoService.performRedo();
    }

    public void cleanup() {
        networkService.cleanup();
    }

    public void setupKeyHandling(TextArea textArea) {
        operationService.setupKeyHandling(textArea);
    }

    public void setRoomInfo(String editRoomId, String readOnlyRoomId, boolean canEdit) {
        this.editRoomId = editRoomId;
        this.readOnlyRoomId = readOnlyRoomId;
        this.canEdit = canEdit;
    }

    // Getters
    public DocumentState getDocumentState() {
        return documentState;
    }

    public NetworkService getNetworkService() {
        return networkService;
    }

    public UndoRedoService getUndoRedoService() {
        return undoRedoService;
    }

    public OperationService getOperationService() {
        return operationService;
    }

    public String getEditRoomId() {
        return editRoomId;
    }

    public String getReadOnlyRoomId() {
        return readOnlyRoomId;
    }

    public boolean canEdit() {
        return canEdit;
    }

    public String getActiveRoomId() {
        return canEdit ? editRoomId : readOnlyRoomId;
    }
}