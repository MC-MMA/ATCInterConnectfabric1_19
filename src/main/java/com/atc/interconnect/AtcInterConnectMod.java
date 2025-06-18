package com.atc.interconnect;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.concurrent.CompletableFuture;

public class AtcInterConnectMod implements ModInitializer {
    public static final String MOD_ID = "atc-interconnect";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static AtcInterConnectMod instance;
    private ConfigManager configManager;
    private WebSocketClient webSocketClient;
    private ApiClient apiClient;
    private EventListener eventListener;

    @Override
    public void onInitialize() {
        instance = this;
        LOGGER.info("=== ATC InterConnect Mod ===");
        LOGGER.info("正在启动服务器互通系统...");

        try {
            // 初始化配置
            File configDir = FabricLoader.getInstance().getConfigDir().toFile();
            configManager = new ConfigManager(new File(configDir, "atc-interconnect.json"));
            configManager.loadConfig();

            // 验证配置
            if (!validateConfig()) {
                LOGGER.error("配置验证失败，请检查配置文件");
                return;
            }

            // 初始化客户端
            apiClient = new ApiClient(
                    configManager.getApiServerUrl(),
                    configManager.getApiKey(),
                    configManager.getApiTimeout()
            );

            webSocketClient = new WebSocketClient(
                    configManager.getWebSocketUrl(),
                    configManager.getApiKey(),
                    this
            );

            // 注册事件监听器
            eventListener = new EventListener(this);
            registerEvents();

            // 注册命令
            CommandRegistrationCallback.EVENT.register(Commands::register);

            LOGGER.info("Mod初始化成功！");

        } catch (Exception e) {
            LOGGER.error("Mod初始化失败", e);
        }
    }

    private boolean validateConfig() {
        if (configManager.getApiKey() == null || configManager.getApiKey().isEmpty()) {
            LOGGER.error("API密钥未配置！请在配置文件中设置api_key");
            return false;
        }

        if (configManager.getApiServerUrl() == null || configManager.getApiServerUrl().isEmpty()) {
            LOGGER.error("API服务器地址未配置！请在配置文件中设置api_server_url");
            return false;
        }

        if (configManager.getWebSocketUrl() == null || configManager.getWebSocketUrl().isEmpty()) {
            LOGGER.error("WebSocket服务器地址未配置！请在配置文件中设置websocket_url");
            return false;
        }

        return true;
    }

    private void registerEvents() {
        // 服务器启动/停止事件
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            // 延迟启动WebSocket连接
            CompletableFuture.runAsync(() -> {
                try {
                    Thread.sleep(2000); // 等待2秒
                    connectToServer();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        });

        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            if (apiClient != null && configManager.isEventEnabled("server_stop")) {
                sendServerEvent("server_stop", "服务器正在关闭");
            }

            if (webSocketClient != null) {
                webSocketClient.disconnect();
            }

            if (apiClient != null) {
                apiClient.shutdown();
            }
        });

        // 修复：使用正确的 lambda 表达式注册事件监听器
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            eventListener.onPlayerJoin(handler, sender, server);
        });

        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            eventListener.onPlayerDisconnect(handler, server);
        });
    }

    private void connectToServer() {
        try {
            if (configManager.isEventEnabled("server_start")) {
                sendServerEvent("server_start", "服务器已启动");
            }

            webSocketClient.connect();

        } catch (Exception e) {
            LOGGER.error("连接服务器失败", e);
        }
    }

    public boolean sendServerEvent(String eventType, String message) {
        return sendServerEvent(eventType, message, null);
    }

    public boolean sendServerEvent(String eventType, String message, Object data) {
        try {
            if (apiClient != null) {
                apiClient.sendEvent(eventType, configManager.getServerName(), message, data);
                return true;
            }
        } catch (Exception e) {
            LOGGER.warn("发送事件失败: " + eventType, e);
        }
        return false;
    }

    public boolean reconnectToServer() {
        if (webSocketClient != null) {
            return webSocketClient.reconnect();
        }
        return false;
    }

    // Getter方法
    public static AtcInterConnectMod getInstance() {
        return instance;
    }

    public ConfigManager getConfigManager() {
        return configManager;
    }

    public WebSocketClient getWebSocketClient() {
        return webSocketClient;
    }

    public ApiClient getApiClient() {
        return apiClient;
    }

    public void reloadConfig() {
        try {
            configManager.loadConfig();
            LOGGER.info("配置已重载");
        } catch (Exception e) {
            LOGGER.error("重载配置失败", e);
        }
    }
}