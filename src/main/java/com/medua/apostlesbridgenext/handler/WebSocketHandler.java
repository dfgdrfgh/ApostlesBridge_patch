package com.medua.apostlesbridgenext.handler;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.enums.ReadyState;
import org.java_websocket.handshake.ServerHandshake;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import com.medua.apostlesbridgenext.client.ApostlesBridgeNextClient;
import com.medua.apostlesbridgenext.config.BridgeConnectionPolicy;
import com.medua.apostlesbridgenext.config.Config;
import com.medua.apostlesbridgenext.config.Ignored;
import com.medua.apostlesbridgenext.types.IgnoredType;
import com.medua.apostlesbridgenext.util.ConfigUtil;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.player.LocalPlayer;

public class WebSocketHandler {
    private static final LogHandler LOGGER = new LogHandler(WebSocketHandler.class);

    WebSocketClient webSocketClient;

    private Timer reconnectTimer;
    private static final int RECONNECT_DELAY = 30_000;
    private boolean reconnectScheduled = false;

    private boolean forceDisconnected = false;

    private String authKey = "";
    private boolean announceNextConnect = false;

    ApostlesBridgeNextClient apostlesBridge;

    private final AtomicBoolean connecting = new AtomicBoolean(false);
    private final AtomicLong connectionGeneration = new AtomicLong(0);

    private Timer pendingConnectTimer;
    private Timer playerWaitTimer;
    private volatile boolean stopping;

    public WebSocketHandler(ApostlesBridgeNextClient apostlesBridge) {
        this(apostlesBridge, true);
    }

    WebSocketHandler(ApostlesBridgeNextClient apostlesBridge, boolean waitForPlayer) {
        this.apostlesBridge = apostlesBridge;
        if (waitForPlayer) {
            waitForPlayerAndConnect();
        }
    }

    private void waitForPlayerAndConnect() {
        playerWaitTimer = new Timer("ApostlesBridge-player-wait", true);
        playerWaitTimer.schedule(new TimerTask() {
            @Override
            public void run() {
                synchronized (WebSocketHandler.this) {
                    if (stopping) {
                        return;
                    }
                    if (Minecraft.getInstance().player != null) {
                        LOGGER.debug("Player detected! Proceeding with WebSocket connection.");
                        if (shouldConnect()) {
                            connect();
                        }
                        playerWaitTimer.cancel();
                    } else {
                        LOGGER.debug("Waiting for player to initialize...");
                    }
                }
            }
        }, 0, 500);
    }

    public synchronized void connect() {
        if (stopping) {
            return;
        }
        if (!canConnect()) {
            LOGGER.warn("Canceled connecting to WebSocket, as the url or the token are unset.");
            return;
        }

        if (Minecraft.getInstance().player == null) {
            return;
        }

        if (webSocketClient != null && webSocketClient.isOpen()) {
            LOGGER.debug("Connect skipped - as it's already connected.");
            return;
        }

        if (!connecting.compareAndSet(false, true)) {
            LOGGER.debug("Connect skipped - as connecting is already in progress.");
            return;
        }

        if (webSocketClient != null && webSocketClient.getReadyState() == ReadyState.CLOSING) {
            connecting.set(false);
            scheduleDelayedConnect();
            return;
        }

        if (webSocketClient != null && !webSocketClient.isClosed()) {
            LOGGER.debug("Closing existing WebSocket connection before reconnecting...");
            try {
                webSocketClient.close();
            } catch (Exception ignored) {
            }
        }

        final long generation = connectionGeneration.incrementAndGet();

        LOGGER.debug("Trying to connect to WebSocket (" + getServerURL() + ") [gen=" + generation + "]");
        sendConnectionDebugMessage("Connecting to WebSocket..");
        try {
            webSocketClient = new WebSocketClient(new URI(getServerURL())) {
                @Override
                public void onOpen(ServerHandshake handshake) {
                    if (stopping || generation != connectionGeneration.get()) {
                        return;
                    }
                    connecting.set(false);
                    LOGGER.debug("Connected to WebSocket! [gen=" + generation + "]");
                    if (announceNextConnect || Config.isConnectionDebugMessagesEnabled()) {
                        MessageHandler.sendSystemMessage("WebSocket connected.");
                    }
                    announceNextConnect = false;
                }

                @Override
                public void onMessage(String messageJson) {
                    if (stopping || generation != connectionGeneration.get()) {
                        return;
                    }
                    try {
                        LOGGER.debug("WebSocket Recieved: " + messageJson + " [gen=" + generation + "]");
                        JsonObject json = new Gson().fromJson(messageJson, JsonObject.class);

                        if (json.has("type")) {
                            String messageType = json.get("type").getAsString();

                            if (messageType.equals("authKey")) {
                                authKey = json.get("authKey").getAsString();
                                LOGGER.debug("Received new auth-key: " + authKey);

                                restartWebSocket();
                            } else if (messageType.equals("message") && json.has("messageData")) {
                                JsonObject messageData = json.getAsJsonObject("messageData");
                                String username = messageData.has("username") ? messageData.get("username").getAsString() : "";
                                String origin = messageData.has("origin") ? messageData.get("origin").getAsString() : "";
                                String originLongname = messageData.has("originLongname") ? messageData.get("originLongname").getAsString() : "";
                                String message = messageData.has("message") ? messageData.get("message").getAsString() : "";
                                String unformattedMessage = messageData.has("unformattedMessage") ? messageData.get("unformattedMessage").getAsString() : "";

                                Ignored ignoredPlayer = new Ignored(username, IgnoredType.PLAYER);
                                Ignored ignoredOrigin = new Ignored(originLongname, IgnoredType.ORIGIN);

                                if (Config.isIgnored(ignoredPlayer) || Config.isIgnored(ignoredOrigin)) {
                                    return;
                                }

                                JsonArray images = messageData.has("images") ? messageData.get("images").getAsJsonArray() : new JsonArray();
                                List<String> urls = new ArrayList<>();

                                for (JsonElement element : images) {
                                    if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
                                        urls.add(element.getAsString());
                                    }
                                }

                                if (Config.getGuild().isEmpty() || (!origin.equalsIgnoreCase(Config.getGuild()) && !originLongname.equalsIgnoreCase(Config.getGuild()))) {
                                    String outputMessage = message;
                                    if (!unformattedMessage.isEmpty()) {
                                        outputMessage = unformattedMessage;
                                        outputMessage = outputMessage.replace("%originColor%", Config.getFormattingColors().getOriginColor());
                                        outputMessage = outputMessage.replace("%origin%", ConfigUtil.getOriginReplacement(origin));
                                        outputMessage = outputMessage.replaceAll("%userColor%", Config.getFormattingColors().getUserColor());
                                        outputMessage = outputMessage.replace("%messageColor%", Config.getFormattingColors().getMessageColor());
                                    }

                                    Minecraft client = Minecraft.getInstance();
                                    String finalOutputMessage = outputMessage;
                                    client.execute(() -> {
                                        if (!stopping && generation == connectionGeneration.get()) {
                                            MessageHandler.sendMessageWithLinks(finalOutputMessage, false, urls);
                                        }
                                    });
                                }
                            }
                        }
                    } catch (JsonSyntaxException e) {
                        LOGGER.error("WebSocket error parsing the response: " + e.getMessage());
                    }
                }

                @Override
                public void onClose(int code, String reason, boolean remote) {
                    if (stopping || generation != connectionGeneration.get()) {
                        return;
                    }
                    connecting.set(false);
                    LOGGER.debug("Disconnected from WebSocket: " + reason + " [gen=" + generation + "]");
                    sendConnectionDebugMessage(connectionClosedMessage(reason));
                    if (shouldConnect()) {
                        scheduleReconnect();
                    }
                }

                @Override
                public void onError(Exception e) {
                    if (stopping || generation != connectionGeneration.get()) {
                        return;
                    }
                    connecting.set(false);
                    LOGGER.error("WebSocket error: " + e.getMessage() + " [gen=" + generation + "]");
                    sendConnectionDebugMessage(connectionFailedMessage(e));
                    if (shouldConnect()) {
                        scheduleReconnect();
                    }
                }
            };
        } catch (URISyntaxException e) {
            connecting.set(false);
            LOGGER.error("An error occured trying to connect to the WebSocket (" + e.getMessage() + ")");
            sendConnectionDebugMessage(connectionFailedMessage(e));
            scheduleReconnect();
            return;
        }

        try {
            webSocketClient.connect();
        } catch (Exception e) {
            connecting.set(false);
            LOGGER.error("Failed to start WebSocket connect: " + e.getMessage());
            sendConnectionDebugMessage(connectionFailedMessage(e));
            scheduleReconnect();
        }
    }

    private boolean canConnect() {
        return !Config.getURL().isEmpty() && !Config.getToken().isEmpty();
    }

    private boolean shouldConnect() {
        if (stopping) {
            return false;
        }
        if (webSocketClient != null && webSocketClient.isOpen()) {
            LOGGER.debug("Reconnect skipped: WebSocket is already connected.");
            return false;
        }
        if (forceDisconnected) {
            LOGGER.debug("Reconnect skipped: WebSocket force disconnected.");
            return false;
        }

        int mode = Config.getGeneralMode();
        if (BridgeConnectionPolicy.isBlockedByGuildChatToggle(mode, Config.isRespectGuildChatToggleEnabled(), Config.isGuildChatEnabled())) {
            LOGGER.debug("WebSocket connection canceled: Respect /g toggle is enabled and guild chat is disabled.");
            return false;
        }

        boolean onHypixel = isOnHypixel();
        boolean shouldConnect = BridgeConnectionPolicy.shouldConnect(mode, Config.isRespectGuildChatToggleEnabled(), Config.isGuildChatEnabled(), onHypixel);
        switch (mode) {
            case 0: // OFF
                LOGGER.debug("WebSocket connection canceled: Mode is OFF.");
                return false;
            case 1: // EVERYWHERE
                return shouldConnect;
            case 2: // HYPIXEL_ONLY
                if (onHypixel) {
                    LOGGER.debug("Player is on Hypixel. Connecting to WebSocket...");
                    return shouldConnect;
                } else {
                    LOGGER.debug("WebSocket connection canceled: Not on Hypixel.");
                    return false;
                }
            default:
                LOGGER.debug("Unknown mode detected. WebSocket will NOT connect.");
                return false;
        }
    }

    private boolean isOnHypixel() {
        ServerData serverInfo = Minecraft.getInstance().getCurrentServer();
        return serverInfo != null && serverInfo.ip != null && serverInfo.ip.contains("hypixel.net");
    }

    private String getServerURL() {
        return getServerURL(Config.getToken());
    }

    private String getServerURL(String token) {
        LocalPlayer player = Minecraft.getInstance().player;
        String username = player != null ? player.getName().getString() : "";
        String uuid = player != null ? player.getStringUUID() : "";

        String serverUrl = Config.getURL().trim();
        while (serverUrl.endsWith("/")) {
            serverUrl = serverUrl.substring(0, serverUrl.length() - 1);
        }

        serverUrl = serverUrl.lastIndexOf(":") > -1 ? serverUrl.substring(0, serverUrl.lastIndexOf(":")) : serverUrl;

        return "wss://" + serverUrl + "?token=" + token + "&authKey=" + authKey + "&username=" + username + "&uuid=" + uuid;
    }

    private synchronized void scheduleReconnect() {
        if (stopping) {
            return;
        }
        if (reconnectTimer != null) {
            reconnectTimer.cancel();
            reconnectTimer.purge();
        }

        reconnectTimer = new Timer("ApostlesBridge-reconnect", true);
        reconnectScheduled = true;
        sendConnectionDebugMessage("Reconnecting to WebSocket in 30 seconds..");
        reconnectTimer.schedule(new TimerTask() {
            @Override
            public void run() {
                reconnectScheduled = false;
                if (shouldConnect()) {
                    LOGGER.info("Reconnecting to WebSocket...");
                    restartWebSocket();
                } else {
                    LOGGER.debug("Reconnect skipped due to mode restrictions or connection status.");
                }
            }
        }, RECONNECT_DELAY);
    }

    private synchronized void scheduleDelayedConnect() {
        if (stopping) {
            return;
        }
        if (pendingConnectTimer != null) {
            pendingConnectTimer.cancel();
            pendingConnectTimer.purge();
        }

        pendingConnectTimer = new Timer("ApostlesBridge-delayed-connect", true);
        pendingConnectTimer.schedule(new TimerTask() {
            @Override
            public void run() {
                if (shouldConnect()) {
                    connect();
                }
            }
        }, 250);
    }

    public String getStatus() {
        if (webSocketClient != null && webSocketClient.isOpen()) {
            return "§aCONNECTED§r";
        } else {
            return "§cDISCONNECTED§r" + (reconnectScheduled ? " §7(⟳ in a moment)§r" : "");
        }
    }

    public boolean isConnected() {
        return webSocketClient != null && webSocketClient.isOpen();
    }

    public boolean canReconnectAfterGuildChatEnabled() {
        return canStartConnection();
    }

    public synchronized void reconnectAfterGuildChatEnabled() {
        announceNextConnect = true;
        restartWebSocket();
    }

    private boolean canStartConnection() {
        return canConnect() && shouldConnect();
    }

    public synchronized void handleConfigSaved(int previousGeneralMode, boolean wasBlockedByGuildChatToggle, boolean connectionSettingsChanged) {
        int currentGeneralMode = Config.getGeneralMode();
        boolean blockedByGuildChatToggle = Config.isBlockedByGuildChatToggle();

        if (!connectionSettingsChanged && wasBlockedByGuildChatToggle == blockedByGuildChatToggle) {
            return;
        }

        if (currentGeneralMode == 0) {
            restartWebSocket();
            return;
        }

        if (blockedByGuildChatToggle) {
            if (previousGeneralMode == 0 || !wasBlockedByGuildChatToggle) {
                RespectGuildToggleMessages.sendSettingMessage("Mode changed, but WebSocket remains paused because ", " is enabled.");
            }
            restartWebSocket();
            return;
        }

        if (previousGeneralMode == 0 || wasBlockedByGuildChatToggle) {
            if (canStartConnection()) {
                MessageHandler.sendSystemMessage("Reconnecting to WebSocket..");
                announceNextConnect = true;
            }
            restartWebSocket();
            return;
        }

        restartWebSocket();
    }

    public synchronized void restartWebSocket() {
        this.restartWebSocket(false);
    }

    public synchronized void restartWebSocket(boolean clearSession) {
        if (stopping) {
            return;
        }
        if (clearSession) {
            authKey = "";
        }

        if (reconnectTimer != null) {
            reconnectTimer.cancel();
            reconnectTimer.purge();
        }
        reconnectScheduled = false;

        if (pendingConnectTimer != null) {
            pendingConnectTimer.cancel();
            pendingConnectTimer.purge();
        }

        connectionGeneration.incrementAndGet();
        connecting.set(false);

        if (webSocketClient != null && !webSocketClient.isClosed()) {
            sendConnectionDebugMessage("WebSocket disconnected for restart.");
            try {
                webSocketClient.close();
            } catch (Exception ignored) {
            }
        }

        if (this.forceDisconnected) {
            this.forceDisconnected = false;
        }

        if (shouldConnect()) {
            scheduleDelayedConnect();
        } else {
            LOGGER.debug("Restart skipped due to mode restrictions.");
        }
    }

    public synchronized void disconnectWebSocket() {
        this.disconnectWebSocket(false);
    }

    public synchronized void disconnectWebSocket(boolean clearSession) {
        if (stopping) {
            return;
        }
        if (clearSession) {
            authKey = "";
        }

        if (reconnectTimer != null) {
            reconnectTimer.cancel();
            reconnectTimer.purge();
        }
        reconnectScheduled = false;

        connectionGeneration.incrementAndGet();
        connecting.set(false);

        if (webSocketClient != null && !webSocketClient.isClosed()) {
            sendConnectionDebugMessage("WebSocket disconnected.");
            try {
                webSocketClient.close();
            } catch (Exception ignored) {
            }
        }

        this.forceDisconnected = true;
    }

    private void sendConnectionDebugMessage(String message) {
        if (!stopping && Config.isConnectionDebugMessagesEnabled()) {
            MessageHandler.sendSystemMessage(message);
        }
    }

    public synchronized void shutdown() {
        if (stopping) {
            return;
        }
        stopping = true;
        connectionGeneration.incrementAndGet();
        connecting.set(false);
        reconnectScheduled = false;
        forceDisconnected = true;
        for (Timer timer : new Timer[] { playerWaitTimer, reconnectTimer, pendingConnectTimer }) {
            if (timer != null) {
                timer.cancel();
            }
        }
        playerWaitTimer = null;
        reconnectTimer = null;
        pendingConnectTimer = null;
        if (webSocketClient != null) {
            try {
                webSocketClient.close();
            } catch (RuntimeException exception) {
                LOGGER.warn("Failed to close WebSocket during shutdown: " + exception.getMessage());
            }
        }
    }

    private String connectionFailedMessage(Exception exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            return "WebSocket connection failed.";
        }
        return "WebSocket connection failed: " + message;
    }

    private String connectionClosedMessage(String reason) {
        if (reason == null || reason.isBlank()) {
            return "WebSocket disconnected.";
        }
        return "WebSocket disconnected: " + reason;
    }
}
