package com.medua.apostlesbridgenext.handler;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import java.lang.reflect.Field;
import java.util.List;

import com.medua.apostlesbridgenext.client.ApostlesBridgeNextClient;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;

class MessageShutdownTest {
    @Test
    void lateMessagesAreDiscardedWithoutAccessingMinecraft() throws Exception {
        Field stopping = ApostlesBridgeNextClient.class.getDeclaredField("stopping");
        stopping.setAccessible(true);
        boolean previous = stopping.getBoolean(null);
        stopping.setBoolean(null, true);
        try {
            assertDoesNotThrow(() -> {
                MessageHandler.sendMessage((Component) null);
                MessageHandler.sendMessageWithLinks("late bridge message", false, List.of("https://example.com/image.png"));
            });
        } finally {
            stopping.setBoolean(null, previous);
        }
    }
}
