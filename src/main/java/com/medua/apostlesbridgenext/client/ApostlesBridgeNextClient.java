package com.medua.apostlesbridgenext.client;

import com.medua.apostlesbridgenext.commands.ApostlesCommand;
import com.medua.apostlesbridgenext.config.Config;
import com.medua.apostlesbridgenext.events.GuildChatToggleEvent;
import com.medua.apostlesbridgenext.events.PlayerJoinEvent;
import com.medua.apostlesbridgenext.generated.gen.BuildConfig;
import com.medua.apostlesbridgenext.handler.ImagePreviewHandler;
import com.medua.apostlesbridgenext.handler.LogHandler;
import com.medua.apostlesbridgenext.handler.WebSocketHandler;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;

public class ApostlesBridgeNextClient implements ClientModInitializer {
    public static final String MODID = BuildConfig.MODID;
    public static final String VERSION = BuildConfig.VERSION;
    private static final LogHandler LOGGER = new LogHandler(ApostlesBridgeNextClient.class);
    private WebSocketHandler webSocketHandler;
    private static volatile boolean stopping;

    public static boolean isStopping() {
        return stopping;
    }

    @Override
    public void onInitializeClient() {
        LOGGER.info(MODID + " v" + VERSION + " initializing..");

        webSocketHandler = new WebSocketHandler(this);
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
            stopping = true;
            webSocketHandler.shutdown();
            ImagePreviewHandler.shutdown();
        });

        // REGISTER COMMANDS
        ApostlesCommand.register(this);

        // REGISTER EVENTS
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            PlayerJoinEvent.onPlayerJoin();
            getWebSocketHandler().restartWebSocket();
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> getWebSocketHandler().restartWebSocket(false));
        ImagePreviewHandler.register();
        GuildChatToggleEvent.register(this);

        // LOAD CONFIG
        Config.loadConfig();
    }

    public WebSocketHandler getWebSocketHandler() {
        return this.webSocketHandler;
    }
}
