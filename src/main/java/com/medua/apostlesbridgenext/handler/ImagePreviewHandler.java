package com.medua.apostlesbridgenext.handler;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.medua.apostlesbridgenext.config.Config;
import com.medua.apostlesbridgenext.client.ApostlesBridgeNextClient;
import com.medua.apostlesbridgenext.util.ImagePreview;
import com.medua.apostlesbridgenext.util.MinecraftClientCompat;

import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.event.Event;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Style;

public final class ImagePreviewHandler {
    public static final int PADDING = 5;
    public static final String IMAGE_PREVIEW_INSERTION = "apostlesbridgenext:image-preview:";

    private static final LogHandler LOGGER = new LogHandler(ImagePreviewHandler.class);
    private static final Map<String, ImagePreview> PREVIEWS = new ConcurrentHashMap<>();
    private static final Set<String> PREVIEWABLE_URLS = Collections.newSetFromMap(new ConcurrentHashMap<>());

    private ImagePreviewHandler() {}

    public static void shutdown() {
        PREVIEWS.values().forEach(ImagePreview::close);
        PREVIEWS.clear();
        PREVIEWABLE_URLS.clear();
    }

    public static void register() {
        ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
            if (screen instanceof ChatScreen) {
                registerRenderEvent(screen);
            }
        });
    }

    private static void registerRenderEvent(Screen screen) {
        if (registerScreenEvent(screen, "afterRender", "net.fabricmc.fabric.api.client.screen.v1.ScreenEvents$AfterRender", "afterRender")) {
            return;
        }

        if (!registerScreenEvent(screen, "afterExtract", "net.fabricmc.fabric.api.client.screen.v1.ScreenEvents$AfterExtract", "afterExtract")) {
            LOGGER.warn("Image preview render hook could not be registered.");
        }
    }

    @SuppressWarnings("unchecked")
    private static boolean registerScreenEvent(Screen screen, String eventMethodName, String callbackClassName, String callbackMethodName) {
        try {
            Method eventMethod = ScreenEvents.class.getMethod(eventMethodName, Screen.class);
            Object event = eventMethod.invoke(null, screen);
            Class<?> callbackClass = Class.forName(callbackClassName);
            Object callback = Proxy.newProxyInstance(callbackClass.getClassLoader(), new Class<?>[] { callbackClass }, renderInvocationHandler(callbackMethodName));

            ((Event<Object>) event).register(callback);
            return true;
        } catch (ReflectiveOperationException | RuntimeException exception) {
            return false;
        }
    }

    private static InvocationHandler renderInvocationHandler(String callbackMethodName) {
        return (proxy, method, args) -> {
            if (method.getName().equals(callbackMethodName) && args != null && args.length >= 5) {
                ImagePreviewHandler.render((Screen) args[0], args[1], (Integer) args[2], (Integer) args[3], ((Number) args[4]).floatValue());
            }
            return null;
        };
    }

    public static void registerImageUrl(String imageUrl) {
        PREVIEWABLE_URLS.add(imageUrl);
        PREVIEWABLE_URLS.add(URI.create(imageUrl).toString());
    }

    private static void render(Screen screen, Object context, int mouseX, int mouseY, float tickDelta) {
        Minecraft client = Minecraft.getInstance();
        if (ApostlesBridgeNextClient.isStopping() || !(screen instanceof ChatScreen) || client.level == null) {
            return;
        }

        String imageUrl = getHoveredImageUrl(context, client, mouseX, mouseY);
        if (imageUrl == null) {
            return;
        }

        ImagePreview preview = PREVIEWS.computeIfAbsent(imageUrl, ImagePreview::new);
        preview.load(client);
        preview.render(context, client, getMaxWidth(client), getMaxHeight(client));
    }

    private static int getMaxWidth(Minecraft client) {
        if (client.hasControlDown()) {
            return Math.max(1, client.getWindow().getGuiScaledWidth() - PADDING * 2 - 2);
        }

        return Config.getImagePreviewSize().maxWidth();
    }

    private static int getMaxHeight(Minecraft client) {
        if (client.hasControlDown()) {
            return Math.max(1, client.getWindow().getGuiScaledHeight() - PADDING * 2 - 2);
        }

        return Config.getImagePreviewSize().maxHeight();
    }

    private static String getHoveredImageUrl(Object context, Minecraft client, int mouseX, int mouseY) {
        Style hoveredStyle = getContextHoveredStyle(context);
        String imageUrl = getImageUrl(hoveredStyle);
        if (imageUrl != null) {
            return imageUrl;
        }

        hoveredStyle = getLegacyHoveredStyle(client, mouseX, mouseY);
        imageUrl = getImageUrl(hoveredStyle);
        if (imageUrl != null) {
            return imageUrl;
        }

        return getImageUrl(getDrawnTextHoveredStyle(client, mouseX, mouseY));
    }

    private static Style getContextHoveredStyle(Object context) {
        for (String fieldName : new String[] { "clickableTextStyle", "hoveredTextStyle", "field_63849", "field_63848" }) {
            try {
                Field field = context.getClass().getDeclaredField(fieldName);
                field.setAccessible(true);
                Object value = field.get(context);
                if (value instanceof Style style) {
                    return style;
                }
            } catch (ReflectiveOperationException | RuntimeException ignored) {
            }
        }
        return null;
    }

    private static Style getLegacyHoveredStyle(Minecraft client, int mouseX, int mouseY) {
        try {
            Object chat = MinecraftClientCompat.getChat(client);
            Method method = getDeclaredMethod(chat.getClass(), new String[] { "getTextStyleAt", "method_1816" }, double.class, double.class);
            method.setAccessible(true);
            return (Style) method.invoke(chat, (double) mouseX, (double) mouseY);
        } catch (ReflectiveOperationException | RuntimeException exception) {
            return null;
        }
    }

    private static Style getDrawnTextHoveredStyle(Minecraft client, int mouseX, int mouseY) {
        try {
            Class<?> consumerClass = forName("net.minecraft.client.gui.ActiveTextCollector", "net.minecraft.client.font.DrawnTextConsumer", "net.minecraft.class_12225");
            Class<?> clickHandlerClass = forName("net.minecraft.client.gui.ActiveTextCollector$ClickableStyleFinder", "net.minecraft.client.font.DrawnTextConsumer$ClickHandler", "net.minecraft.class_12225$class_12226");
            Class<?> textRendererClass = forName("net.minecraft.client.gui.Font", "net.minecraft.client.font.TextRenderer", "net.minecraft.class_327");
            Constructor<?> constructor = clickHandlerClass.getDeclaredConstructor(textRendererClass, int.class, int.class);
            constructor.setAccessible(true);
            Object clickHandler = constructor.newInstance(client.font, mouseX, mouseY);

            Method insertMethod = getDeclaredMethod(clickHandlerClass, new String[] { "includeInsertions", "insert", "method_76756" }, boolean.class);
            insertMethod.setAccessible(true);
            clickHandler = insertMethod.invoke(clickHandler, true);

            Object chat = MinecraftClientCompat.getChat(client);
            captureClickableText(chat, clickHandler, consumerClass, client.getWindow().getGuiScaledHeight(), MinecraftClientCompat.getGuiTicks(client));

            Method getStyleMethod = getDeclaredMethod(clickHandlerClass, new String[] { "result", "getStyle", "method_75777" });
            getStyleMethod.setAccessible(true);
            return (Style) getStyleMethod.invoke(clickHandler);
        } catch (ReflectiveOperationException | RuntimeException exception) {
            return null;
        }
    }

    private static void captureClickableText(Object chat, Object clickHandler, Class<?> consumerClass, int height, int ticks)
            throws ReflectiveOperationException {
        try {
            Class<?> displayModeClass = Class.forName("net.minecraft.client.gui.components.ChatComponent$DisplayMode");
            Method method = getDeclaredMethod(chat.getClass(), new String[] { "captureClickableText", "render", "method_75803" },
                consumerClass, int.class, int.class, displayModeClass);
            method.setAccessible(true);
            method.invoke(chat, clickHandler, height, ticks, displayModeClass.getField("FOREGROUND").get(null));
            return;
        } catch (ClassNotFoundException | NoSuchMethodException ignored) {
        }

        Method method = getDeclaredMethod(chat.getClass(), new String[] { "captureClickableText", "render", "method_75803" },
            consumerClass, int.class, int.class, boolean.class);
        method.setAccessible(true);
        method.invoke(chat, clickHandler, height, ticks, true);
    }

    private static Class<?> forName(String... classNames) throws ClassNotFoundException {
        ClassNotFoundException lastException = null;
        for (String className : classNames) {
            try {
                return Class.forName(className);
            } catch (ClassNotFoundException exception) {
                lastException = exception;
            }
        }
        throw lastException == null ? new ClassNotFoundException() : lastException;
    }

    private static Method getDeclaredMethod(Class<?> owner, String[] names, Class<?>... parameterTypes) throws NoSuchMethodException {
        NoSuchMethodException lastException = null;
        for (String name : names) {
            try {
                return owner.getDeclaredMethod(name, parameterTypes);
            } catch (NoSuchMethodException exception) {
                lastException = exception;
            }
        }
        throw lastException == null ? new NoSuchMethodException(owner.getName()) : lastException;
    }

    private static String getImageUrl(Style hoveredStyle) {
        if (hoveredStyle != null && hoveredStyle.getInsertion() != null && hoveredStyle.getInsertion().startsWith(IMAGE_PREVIEW_INSERTION)) {
            return hoveredStyle.getInsertion().substring(IMAGE_PREVIEW_INSERTION.length());
        }

        if (hoveredStyle == null || !(hoveredStyle.getClickEvent() instanceof ClickEvent.OpenUrl openUrl)) {
            return null;
        }

        String imageUrl = openUrl.uri().toString();
        if (PREVIEWABLE_URLS.contains(imageUrl) || isPreviewImageUrl(imageUrl)) {
            return imageUrl;
        }
        return null;
    }

    private static boolean isPreviewImageUrl(String url) {
        try {
            URI uri = URI.create(url);
            String scheme = uri.getScheme();
            if (scheme == null || (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https"))) {
                return false;
            }

            String path = uri.getPath();
            if (path == null) {
                return false;
            }

            String lowerPath = path.toLowerCase(Locale.ROOT);
            return lowerPath.endsWith(".png") || lowerPath.endsWith(".jpg") || lowerPath.endsWith(".jpeg") || lowerPath.endsWith(".gif") || lowerPath.endsWith(".webp");
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }
}
