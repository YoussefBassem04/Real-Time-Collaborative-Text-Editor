package org.example.client;

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

    public EditorController() {
        this.documentState = new DocumentState();
        this.networkService = new NetworkService(this);
        this.undoRedoService = new UndoRedoService(this);
        this.operationService = new OperationService(this);
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

    public void handleLocalChange(String oldText, String newText) {
        operationService.handleLocalChange(oldText, newText);
    }

    public void handleRemoteMessage(EditorMessage message) {
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