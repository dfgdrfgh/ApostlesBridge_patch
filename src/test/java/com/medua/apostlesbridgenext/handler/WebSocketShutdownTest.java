package com.medua.apostlesbridgenext.handler;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Field;
import java.net.URI;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.junit.jupiter.api.Test;

class WebSocketShutdownTest {
    @Test
    void shutdownCancelsEveryTimerAndInvalidatesCallbacks() throws Exception {
        WebSocketHandler handler = new WebSocketHandler(null, false);
        Timer[] timers = { new Timer(true), new Timer(true), new Timer(true) };
        set(handler, "playerWaitTimer", timers[0]);
        set(handler, "reconnectTimer", timers[1]);
        set(handler, "pendingConnectTimer", timers[2]);
        set(handler, "reconnectScheduled", true);
        AtomicBoolean connecting = (AtomicBoolean) get(handler, "connecting");
        AtomicLong generation = (AtomicLong) get(handler, "connectionGeneration");
        connecting.set(true);
        generation.set(7);
        RecordingSocket socket = new RecordingSocket();
        handler.webSocketClient = socket;

        try {
            handler.shutdown();
            assertEquals(1, socket.closeCount);
            assertEquals(8, generation.get());
            assertFalse(connecting.get());
            assertFalse((boolean) get(handler, "reconnectScheduled"));
            for (Timer timer : timers) {
                assertThrows(IllegalStateException.class, () -> timer.schedule(new TimerTask() {
                    @Override public void run() { fail("Cancelled timer ran"); }
                }, 60_000));
            }
            // Disconnect is also emitted during Minecraft shutdown. It must not restart
            // the bridge, read Minecraft state, or create another timer after shutdown.
            assertDoesNotThrow(() -> {
                handler.restartWebSocket(false);
                handler.connect();
                handler.disconnectWebSocket();
                handler.shutdown();
            });
            assertEquals(1, socket.closeCount);
            assertNull(get(handler, "playerWaitTimer"));
            assertNull(get(handler, "reconnectTimer"));
            assertNull(get(handler, "pendingConnectTimer"));
        } finally {
            for (Timer timer : timers) timer.cancel();
        }
    }

    private static Object get(Object target, String name) throws Exception {
        Field field = WebSocketHandler.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static void set(Object target, String name, Object value) throws Exception {
        Field field = WebSocketHandler.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static final class RecordingSocket extends WebSocketClient {
        int closeCount;
        RecordingSocket() { super(URI.create("ws://localhost")); }
        @Override public void close() { closeCount++; }
        @Override public void onOpen(ServerHandshake handshake) { }
        @Override public void onMessage(String message) { }
        @Override public void onClose(int code, String reason, boolean remote) { }
        @Override public void onError(Exception exception) { }
    }
}
