package com.arachnaid.manager;

import com.arachnaid.ArachnaidPlugin;
import com.sun.net.httpserver.HttpServer;
import net.kyori.adventure.resource.ResourcePackInfo;
import net.kyori.adventure.resource.ResourcePackRequest;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;

public final class ResourcePackManager {

    private static final String BUNDLED_PATH = "resourcepack/ArachnaidAxe-ResourcePack.zip";

    private final ArachnaidPlugin plugin;
    private HttpServer httpServer;
    private byte[] cachedPackBytes;
    private String cachedSha1Hex;
    private String activePackUrl;

    public ResourcePackManager(ArachnaidPlugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        stop();

        if (!plugin.getConfig().getBoolean("resource-pack.enabled", true)) {
            return;
        }

        File outFile = extractBundledPack();
        if (outFile == null || !outFile.exists()) {
            plugin.getLogger().warning("Bundled resource pack could not be extracted.");
            return;
        }

        try {
            this.cachedPackBytes = Files.readAllBytes(outFile.toPath());
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            this.cachedSha1Hex = HexFormat.of().formatHex(md.digest(cachedPackBytes));
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to read/hash resource pack: " + e.getMessage());
            return;
        }

        String customUrl = plugin.getConfig().getString("resource-pack.custom-url", "").trim();
        if (!customUrl.isEmpty()) {
            this.activePackUrl = customUrl;
            plugin.getLogger().info("Using custom resource pack URL: " + activePackUrl);
            return;
        }

        if (!plugin.getConfig().getBoolean("resource-pack.use-built-in-host", true)) {
            return;
        }

        int port = plugin.getConfig().getInt("resource-pack.port", 25580);
        String host = resolvePublicHost();

        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("0.0.0.0", port), 0);
            server.createContext("/arachnaid-pack.zip", exchange -> {
                if (!"GET".equalsIgnoreCase(exchange.getRequestMethod()) && !"HEAD".equalsIgnoreCase(exchange.getRequestMethod())) {
                    exchange.sendResponseHeaders(405, -1);
                    exchange.close();
                    return;
                }
                exchange.getResponseHeaders().set("Content-Type", "application/zip");
                exchange.getResponseHeaders().set("Cache-Control", "no-cache");
                if ("HEAD".equalsIgnoreCase(exchange.getRequestMethod())) {
                    exchange.sendResponseHeaders(200, -1);
                } else {
                    exchange.sendResponseHeaders(200, cachedPackBytes.length);
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(cachedPackBytes);
                    }
                }
                exchange.close();
            });
            server.setExecutor(null);
            server.start();
            this.httpServer = server;
            this.activePackUrl = "http://" + host + ":" + port + "/arachnaid-pack.zip";
            plugin.getLogger().info("Bundled Resource Pack server started at: " + activePackUrl + " (SHA-1: " + cachedSha1Hex + ")");
        } catch (IOException e) {
            plugin.getLogger().warning("Could not bind built-in Resource Pack HTTP port " + port + ": " + e.getMessage()
                    + ". Set 'resource-pack.custom-url' in config.yml if your host only allows 1 port.");
        }
    }

    public void stop() {
        if (httpServer != null) {
            httpServer.stop(0);
            httpServer = null;
        }
        activePackUrl = null;
    }

    public void sendPack(Player player) {
        if (!plugin.getConfig().getBoolean("resource-pack.enabled", true)) {
            return;
        }
        if (activePackUrl == null || activePackUrl.isBlank() || cachedSha1Hex == null) {
            return;
        }

        try {
            UUID packUuid = UUID.nameUUIDFromBytes(("arachnaid-pack-" + cachedSha1Hex).getBytes(StandardCharsets.UTF_8));
            ResourcePackInfo info = ResourcePackInfo.resourcePackInfo()
                    .id(packUuid)
                    .uri(URI.create(activePackUrl))
                    .hash(cachedSha1Hex)
                    .build();

            boolean required = plugin.getConfig().getBoolean("resource-pack.required", false);
            String promptRaw = plugin.getConfig().getString("resource-pack.prompt",
                    "<dark_red><bold>Arachnaid Axe</bold></dark_red> <gray>Custom weapon texture pack</gray>");
            Component prompt = plugin.parse(promptRaw);

            ResourcePackRequest request = ResourcePackRequest.resourcePackRequest()
                    .packs(info)
                    .required(required)
                    .prompt(prompt)
                    .replace(true)
                    .build();

            player.sendResourcePacks(request);
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to send resource pack to " + player.getName() + ": " + e.getMessage());
        }
    }

    private File extractBundledPack() {
        if (!plugin.getDataFolder().exists()) {
            plugin.getDataFolder().mkdirs();
        }
        File target = new File(plugin.getDataFolder(), "ArachnaidAxe-ResourcePack.zip");
        try (InputStream in = plugin.getResource(BUNDLED_PATH)) {
            if (in == null) {
                return null;
            }
            Files.write(target.toPath(), in.readAllBytes());
            return target;
        } catch (IOException e) {
            plugin.getLogger().warning("Failed to extract bundled resource pack: " + e.getMessage());
            return null;
        }
    }

    private String resolvePublicHost() {
        String configured = plugin.getConfig().getString("resource-pack.public-host", "").trim();
        if (!configured.isEmpty()) {
            return configured;
        }
        String serverIp = Bukkit.getIp();
        if (serverIp != null && !serverIp.isBlank() && !"0.0.0.0".equals(serverIp)) {
            return serverIp;
        }
        try {
            return InetAddress.getLocalHost().getHostAddress();
        } catch (Exception ignored) {
            return "127.0.0.1";
        }
    }
}
