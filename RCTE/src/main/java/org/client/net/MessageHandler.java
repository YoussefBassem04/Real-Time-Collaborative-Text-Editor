package net;



import javafx.application.Platform;
import ui.CollaborativeEditorUI;

public class MessageHandler {

    private final CollaborativeEditorUI ui;

    public MessageHandler(CollaborativeEditorUI ui) {
        this.ui = ui;
    }

    public void handle(String jsonMessage) {
        // Simplified example, assumes plain update string:
        if (jsonMessage.startsWith("UPDATE:")) {
            String content = jsonMessage.substring(7);
            Platform.runLater(() -> ui.updateEditorContent(content));
        } else if (jsonMessage.startsWith("USER:")) {
            // Handle user list update (optional)
        }
    }
}

