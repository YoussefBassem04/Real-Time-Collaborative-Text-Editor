package org.client.net;



import javax.websocket.*;
import java.net.URI;
import java.util.function.Consumer;

@ClientEndpoint
public class WebSocketClientEndpoint {

    private Session session;
    private Consumer<String> onMessageHandler;

    public void connect(String serverUri) throws Exception {
        WebSocketContainer container = ContainerProvider.getWebSocketContainer();
        container.connectToServer(this, new URI(serverUri));
    }

    @OnOpen
    public void onOpen(Session session) {
        this.session = session;
        System.out.println("[WS] Connected to server");
    }

    @OnMessage
    public void onMessage(String message) {
        if (onMessageHandler != null) {
            onMessageHandler.accept(message);
        }
    }

    public void setOnMessageHandler(Consumer<String> handler) {
        this.onMessageHandler = handler;
    }

    public void send(String message) {
        if (session != null && session.isOpen()) {
            session.getAsyncRemote().sendText(message);
        }
    }

    @OnClose
    public void onClose(Session session, CloseReason reason) {
        System.out.println("[WS] Disconnected: " + reason);
    }

    @OnError
    public void onError(Session session, Throwable throwable) {
        throwable.printStackTrace();
    }
}
