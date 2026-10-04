package com.medua.apostlesbridgenext.util;

import com.medua.apostlesbridgenext.client.ApostlesBridgeNextClient;
import com.medua.apostlesbridgenext.handler.ImagePreviewHandler;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ImagePreview implements AutoCloseable {
    private static final Pattern META_IMAGE_PATTERN = Pattern.compile(
        "<meta\\s+[^>]*(?:property|name)=[\"'](?:og:image|twitter:image)[\"'][^>]*content=[\"']([^\"']+)[\"'][^>]*>|" +
        "<meta\\s+[^>]*content=[\"']([^\"']+)[\"'][^>]*(?:property|name)=[\"'](?:og:image|twitter:image)[\"'][^>]*>",
        Pattern.CASE_INSENSITIVE
    );

    private final String url;
    private final Object textureId;
    private volatile boolean loading;
    private volatile boolean failed;
    private volatile String failureReason = "Image preview failed";
    private volatile boolean gif;
    private volatile String videoLabel;
    private volatile int width;
    private volatile int height;
    private boolean closed;
    private NativeImage pendingImage;

    public ImagePreview(String url) {
        this.url = url;
        this.textureId = MinecraftReflectionUtil.createResourceId(ApostlesBridgeNextClient.MODID, "image_preview/" + sha1(url));
    }

    public synchronized void load(Minecraft client) {
        if (closed || ApostlesBridgeNextClient.isStopping() || loading || failed || width > 0) {
            return;
        }

        loading = true;
        CompletableFuture.supplyAsync(this::download).whenComplete((result, throwable) -> finishDownload(client, result, throwable));
    }

    private synchronized void finishDownload(Minecraft client, DownloadResult result, Throwable throwable) {
        if (throwable != null || result.image() == null) {
            failureReason = throwable != null ? shortReason(throwable) : result.error();
            failed = true;
            loading = false;
            return;
        }
        if (closed || ApostlesBridgeNextClient.isStopping()) {
            result.image().close();
            loading = false;
            return;
        }
        pendingImage = result.image();
        client.execute(() -> upload(client, result));
    }

    private synchronized void upload(Minecraft client, DownloadResult result) {
        if (closed || ApostlesBridgeNextClient.isStopping()) {
            close();
            return;
        }
        try {
            gif = result.gif();
            videoLabel = result.videoLabel();
            width = pendingImage.getWidth();
            height = pendingImage.getHeight();
            DynamicTexture texture = new DynamicTexture(() -> url, pendingImage);
            pendingImage = null;
            // Registered textures are closed by Minecraft's texture manager.
            registerTexture(client.getTextureManager(), textureId, texture);
        } finally {
            loading = false;
        }
    }

    @Override
    public synchronized void close() {
        closed = true;
        if (pendingImage != null) {
            pendingImage.close();
            pendingImage = null;
        }
        loading = false;
    }

    public void render(Object context, Minecraft client, int maxWidth, int maxHeight) {
        int previewWidth = width;
        int previewHeight = height;
        if (failed) {
            drawMessage(context, client, failureReason);
            return;
        }
        if (previewWidth <= 0 || previewHeight <= 0) {
            drawMessage(context, client, "Loading image..");
            return;
        }

        float scale = Math.min((float) maxWidth / previewWidth, (float) maxHeight / previewHeight);
        scale = Math.min(scale, 1.0f);
        int scaledWidth = Math.max(1, Math.round(previewWidth * scale));
        int scaledHeight = Math.max(1, Math.round(previewHeight * scale));

        drawFrame(context, scaledWidth, scaledHeight);
        drawTexture(context, textureId, ImagePreviewHandler.PADDING + 1, ImagePreviewHandler.PADDING + 1, 0, 0, scaledWidth, scaledHeight, previewWidth, previewHeight, previewWidth, previewHeight);
        if (videoLabel != null) {
            drawBadge(context, client, videoLabel);
        } else if (gif) {
            drawGifBadge(context, client);
        }
    }

    private DownloadResult download() {
        DownloadResult lastResult = new DownloadResult(null, "Image preview failed", false, null);
        for (URI candidateUri : previewCandidates(url)) {
            lastResult = download(candidateUri);
            if (lastResult.image() != null) {
                return lastResult;
            }
        }
        return lastResult;
    }

    private DownloadResult download(URI uri) {
        return download(uri, 0);
    }

    private DownloadResult download(URI uri, int htmlPreviewDepth) {
        try {
            URLConnection connection = openConnectionWithRedirects(uri, 0);
            String contentType = connection.getContentType();
            byte[] bytes;

            try (InputStream stream = connection.getInputStream()) {
                bytes = readBytes(stream);
            }

            NativeImage image = decodeImage(bytes);
            if (image != null) {
                return new DownloadResult(image, null, isGifUrl(uri.toString()) || isGif(contentType, bytes), videoLabel(url));
            } else if (isHtml(contentType) && htmlPreviewDepth < 2) {
                URI previewImage = extractHtmlPreviewImage(uri, bytes);
                if (previewImage != null) {
                    return download(previewImage, htmlPreviewDepth + 1);
                }
            } else {
                String type = contentType == null ? "unknown content" : contentType.split(";")[0];
                return new DownloadResult(null, "Preview decode failed: " + type, false, null);
            }
            String type = contentType == null ? "unknown content" : contentType.split(";")[0];
            return new DownloadResult(null, "Preview decode failed: " + type, false, null);
        } catch (IOException | IllegalArgumentException exception) {
            return new DownloadResult(null, shortReason(exception), false, null);
        }
    }

    private static NativeImage decodeImage(byte[] bytes) {
        try {
            return NativeImage.read(bytes);
        } catch (IOException ignored) {
            return decodeWithImageIo(bytes);
        }
    }

    private static NativeImage decodeWithImageIo(byte[] bytes) {
        try {
            BufferedImage bufferedImage = ImageIO.read(new ByteArrayInputStream(bytes));
            if (bufferedImage == null) {
                return null;
            }

            NativeImage image = new NativeImage(bufferedImage.getWidth(), bufferedImage.getHeight(), false);
            for (int y = 0; y < bufferedImage.getHeight(); y++) {
                for (int x = 0; x < bufferedImage.getWidth(); x++) {
                    image.setPixel(x, y, bufferedImage.getRGB(x, y));
                }
            }
            return image;
        } catch (IOException | RuntimeException exception) {
            return null;
        }
    }

    private static URLConnection openConnectionWithRedirects(URI uri, int redirectCount) throws IOException {
        if (redirectCount > 5) {
            throw new IOException("Too many redirects");
        }

        URLConnection connection = uri.toURL().openConnection();
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(10000);
        connection.setRequestProperty("Accept", "image/png,image/jpeg,image/jpg,image/*;q=0.8,*/*;q=0.5");
        connection.setRequestProperty("User-Agent", "Mozilla/5.0 ApostlesBridgeNext/" + ApostlesBridgeNextClient.VERSION);

        if (!(connection instanceof HttpURLConnection httpConnection)) {
            return connection;
        }

        httpConnection.setInstanceFollowRedirects(false);
        int status = httpConnection.getResponseCode();
        if (status >= 300 && status < 400) {
            String location = httpConnection.getHeaderField("Location");
            httpConnection.disconnect();
            if (location == null || location.isBlank()) {
                throw new IOException("Redirect without location");
            }
            return openConnectionWithRedirects(uri.resolve(location), redirectCount + 1);
        }
        if (status >= 400) {
            httpConnection.disconnect();
            throw new IOException("HTTP " + status);
        }

        return httpConnection;
    }

    private static byte[] readBytes(InputStream stream) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = stream.read(buffer)) != -1) {
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private static String shortReason(Throwable throwable) {
        String message = throwable.getMessage();
        if (message == null || message.isBlank()) {
            return "Image preview failed";
        }
        return "Preview failed: " + message;
    }

    private static boolean isGif(String contentType, byte[] bytes) {
        if (contentType != null && contentType.toLowerCase().startsWith("image/gif")) {
            return true;
        }
        return bytes.length >= 6
                && bytes[0] == 'G'
                && bytes[1] == 'I'
                && bytes[2] == 'F'
                && bytes[3] == '8'
                && (bytes[4] == '7' || bytes[4] == '9')
                && bytes[5] == 'a';
    }

    private static boolean isHtml(String contentType) {
        return contentType != null && contentType.toLowerCase().startsWith("text/html");
    }

    private static URI extractHtmlPreviewImage(URI pageUri, byte[] bytes) {
        String html = new String(bytes, StandardCharsets.UTF_8);
        Matcher matcher = META_IMAGE_PATTERN.matcher(html);
        if (!matcher.find()) {
            return null;
        }

        String value = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
        if (value == null || value.isBlank()) {
            return null;
        }

        try {
            return pageUri.resolve(unescapeHtml(value.trim()));
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private static String unescapeHtml(String value) {
        return value
                .replace("&amp;", "&")
                .replace("&#38;", "&")
                .replace("&quot;", "\"")
                .replace("&#34;", "\"")
                .replace("&#39;", "'")
                .replace("&apos;", "'");
    }

    private static boolean isGifUrl(String value) {
        try {
            String path = URI.create(value).getPath();
            return path != null && path.toLowerCase().endsWith(".gif");
        } catch (IllegalArgumentException exception) {
            return value.toLowerCase().contains(".gif");
        }
    }

    private static String videoLabel(String value) {
        try {
            String path = URI.create(value).getPath();
            return videoExtensionLabel(path);
        } catch (IllegalArgumentException exception) {
            return videoExtensionLabel(value);
        }
    }

    private static String videoExtensionLabel(String value) {
        if (value == null) {
            return null;
        }

        String lowerValue = value.toLowerCase();
        if (lowerValue.endsWith(".mp4") || lowerValue.contains(".mp4?")) {
            return "MP4";
        }
        if (lowerValue.endsWith(".webm") || lowerValue.contains(".webm?")) {
            return "WEBM";
        }
        if (lowerValue.endsWith(".mov") || lowerValue.contains(".mov?")) {
            return "MOV";
        }
        return null;
    }

    private static List<URI> previewCandidates(String value) {
        Set<URI> candidates = new LinkedHashSet<>();
        try {
            URI uri = URI.create(value);
            addCandidate(candidates, uri);
            addDiscordAttachmentCandidates(candidates, uri);
            addVideoThumbnailCandidates(candidates, uri);
        } catch (IllegalArgumentException ignored) {
        }
        return new ArrayList<>(candidates);
    }

    private static void addDiscordAttachmentCandidates(Set<URI> candidates, URI uri) {
        String host = host(uri);
        String path = uri.getPath();
        if (path == null || !path.startsWith("/attachments/")) {
            return;
        }

        if (host.equals("media.discordapp.net") || host.equals("cdn.discordapp.com")) {
            addCandidate(candidates, withDiscordFormat(uri, "png"));
            addCandidate(candidates, withDiscordFormat(uri, "jpg"));
            addCandidate(candidates, withHost(uri, "cdn.discordapp.com"));
            addCandidate(candidates, withHost(withDiscordFormat(uri, "png"), "cdn.discordapp.com"));
            addCandidate(candidates, withHost(withDiscordFormat(uri, "jpg"), "cdn.discordapp.com"));
            addCandidate(candidates, withoutQuery(uri));
            addCandidate(candidates, withHost(withoutQuery(uri), "cdn.discordapp.com"));
        }
    }

    private static void addVideoThumbnailCandidates(Set<URI> candidates, URI uri) {
        if (videoLabel(uri.toString()) == null) {
            return;
        }

        addCandidate(candidates, withQuery(uri, "format=webp"));
        addCandidate(candidates, withQuery(uri, "format=png"));
        addExtensionCandidates(candidates, uri);

        URI embeddedUrl = embeddedDiscordExternalUrl(uri);
        if (embeddedUrl != null) {
            addCandidate(candidates, embeddedUrl);
            addExtensionCandidates(candidates, embeddedUrl);
        }
    }

    private static void addExtensionCandidates(Set<URI> candidates, URI uri) {
        addCandidate(candidates, withExtension(uri, ".gif"));
        addCandidate(candidates, withExtension(uri, ".webp"));
        addCandidate(candidates, withExtension(uri, ".png"));
        addCandidate(candidates, withExtension(uri, ".jpg"));
    }

    private static URI embeddedDiscordExternalUrl(URI uri) {
        String host = host(uri);
        String path = uri.getPath();
        if (!host.endsWith("discordapp.net") || path == null) {
            return null;
        }

        int markerIndex = path.indexOf("/https/");
        if (markerIndex < 0) {
            return null;
        }

        try {
            return URI.create("https://" + path.substring(markerIndex + "/https/".length()));
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private static URI withoutQuery(URI uri) {
        try {
            return new URI(uri.getScheme(), uri.getAuthority(), uri.getPath(), null, uri.getFragment());
        } catch (Exception exception) {
            return uri;
        }
    }

    private static URI withHost(URI uri, String host) {
        try {
            return new URI(uri.getScheme(), uri.getUserInfo(), host, uri.getPort(), uri.getPath(), uri.getQuery(), uri.getFragment());
        } catch (Exception exception) {
            return uri;
        }
    }

    private static URI withQuery(URI uri, String query) {
        try {
            return new URI(uri.getScheme(), uri.getAuthority(), uri.getPath(), query, uri.getFragment());
        } catch (Exception exception) {
            return uri;
        }
    }

    private static URI withDiscordFormat(URI uri, String format) {
        String query = uri.getRawQuery();
        if (query == null || query.isBlank()) {
            return withQuery(uri, "format=" + format + "&quality=lossless");
        }

        String[] parts = query.split("&");
        StringBuilder builder = new StringBuilder();
        boolean replacedFormat = false;
        boolean replacedQuality = false;
        for (String part : parts) {
            if (part.isBlank() || part.equals("=")) {
                continue;
            }

            String replacement = part;
            if (part.startsWith("format=")) {
                replacement = "format=" + format;
                replacedFormat = true;
            } else if (part.startsWith("quality=")) {
                replacement = "quality=lossless";
                replacedQuality = true;
            }

            if (!builder.isEmpty()) {
                builder.append('&');
            }
            builder.append(replacement);
        }

        if (!replacedFormat) {
            if (!builder.isEmpty()) {
                builder.append('&');
            }
            builder.append("format=").append(format);
        }
        if (!replacedQuality) {
            builder.append("&quality=lossless");
        }

        return withQuery(uri, builder.toString());
    }

    private static URI withExtension(URI uri, String extension) {
        String path = uri.getPath();
        if (path == null) {
            return uri;
        }

        int extensionIndex = path.lastIndexOf('.');
        if (extensionIndex < 0) {
            return uri;
        }

        try {
            return new URI(uri.getScheme(), uri.getAuthority(), path.substring(0, extensionIndex) + extension, uri.getQuery(), uri.getFragment());
        } catch (Exception exception) {
            return uri;
        }
    }

    private static String host(URI uri) {
        String host = uri.getHost();
        return host == null ? "" : host.toLowerCase();
    }

    private static void addCandidate(Set<URI> candidates, URI uri) {
        if (uri != null) {
            candidates.add(uri);
        }
    }

    private static void drawMessage(Object context, Minecraft client, String message) {
        int textWidth = client.font.width(message);
        drawFrame(context, textWidth + 8, client.font.lineHeight + 8);
        drawText(context, client.font, message, ImagePreviewHandler.PADDING + 5, ImagePreviewHandler.PADDING + 5, ColorUtil.TEXT_WHITE, true);
    }

    private static void drawGifBadge(Object context, Minecraft client) {
        drawBadge(context, client, "GIF");
    }

    private static void drawBadge(Object context, Minecraft client, String label) {
        int left = ImagePreviewHandler.PADDING + 4;
        int top = ImagePreviewHandler.PADDING + 4;
        int right = left + client.font.width(label) + 8;
        int bottom = top + client.font.lineHeight + 5;

        fill(context, left, top, right, bottom, ColorUtil.DARK_PURPLE_BADGE);
        drawText(context, client.font, label, left + 4, top + 3, ColorUtil.TEXT_WHITE, true);
    }

    private static void drawFrame(Object context, int width, int height) {
        int left = ImagePreviewHandler.PADDING;
        int top = ImagePreviewHandler.PADDING;
        int right = left + width + 2;
        int bottom = top + height + 2;

        fill(context, left, top, right, bottom, ColorUtil.IMAGE_PREVIEW_BACKGROUND);
        horizontalLine(context, left, right - 1, top, ColorUtil.DARK_PURPLE_BORDER);
        horizontalLine(context, left, right - 1, bottom - 1, ColorUtil.DARK_PURPLE_BORDER);
        verticalLine(context, left, top, bottom - 1, ColorUtil.DARK_PURPLE_BORDER);
        verticalLine(context, right - 1, top, bottom - 1, ColorUtil.DARK_PURPLE_BORDER);
    }

    private static void drawTexture(Object context, Object textureId, int x, int y, float u, float v, int width, int height, int regionWidth, int regionHeight, int textureWidth, int textureHeight) {
        MinecraftReflectionUtil.invokeAny(context, new String[]{"blit", "drawTexture", "method_25302"},
            new Class<?>[]{RenderPipelines.GUI_TEXTURED.getClass(), textureId.getClass(), int.class, int.class, float.class, float.class, int.class, int.class, int.class, int.class, int.class, int.class},
            RenderPipelines.GUI_TEXTURED, textureId, x, y, u, v, width, height, regionWidth, regionHeight, textureWidth, textureHeight);
    }

    private static void drawText(Object context, Object font, String text, int x, int y, int color, boolean shadow) {
        MinecraftReflectionUtil.invokeAny(context, new String[]{"text", "drawString", "drawText", "method_51433"},
            new Class<?>[]{font.getClass(), String.class, int.class, int.class, int.class, boolean.class},
            font, text, x, y, color, shadow);
    }

    private static void fill(Object context, int left, int top, int right, int bottom, int color) {
        MinecraftReflectionUtil.invokeAny(context, new String[]{"fill", "method_25294"}, new Class<?>[]{int.class, int.class, int.class, int.class, int.class}, left, top, right, bottom, color);
    }

    private static void horizontalLine(Object context, int left, int right, int y, int color) {
        MinecraftReflectionUtil.invokeAny(context, new String[]{"horizontalLine", "hLine", "drawHorizontalLine", "method_51738"}, new Class<?>[]{int.class, int.class, int.class, int.class}, left, right, y, color);
    }

    private static void verticalLine(Object context, int x, int top, int bottom, int color) {
        MinecraftReflectionUtil.invokeAny(context, new String[]{"verticalLine", "vLine", "drawVerticalLine", "method_51742"}, new Class<?>[]{int.class, int.class, int.class, int.class}, x, top, bottom, color);
    }

    private static void registerTexture(Object textureManager, Object textureId, DynamicTexture texture) {
        MinecraftReflectionUtil.invokeAny(textureManager, new String[]{"register", "method_4616"}, new Class<?>[]{textureId.getClass(), texture.getClass()}, textureId, texture);
    }

    private static String sha1(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                builder.append(String.format("%02x", b));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException exception) {
            return Integer.toHexString(value.hashCode());
        }
    }

    private record DownloadResult(NativeImage image, String error, boolean gif, String videoLabel) { }
}
