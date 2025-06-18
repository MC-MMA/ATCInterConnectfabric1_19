package com.atc.interconnect;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.annotations.SerializedName;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

public class ConfigManager {
    private final File configFile;
    private final Gson gson;
    private Config config;

    public ConfigManager(File configFile) {
        this.configFile = configFile;
        this.gson = new GsonBuilder().setPrettyPrinting().create();
        this.config = new Config();
    }

    public void loadConfig() {
        try {
            if (!configFile.exists()) {
                saveDefaultConfig();
            } else {
                try (FileReader reader = new FileReader(configFile)) {
                    config = gson.fromJson(reader, Config.class);
                    if (config == null) {
                        config = new Config();
                    }
                }
            }

            AtcInterConnectMod.LOGGER.info("配置加载完成:");
            AtcInterConnectMod.LOGGER.info("服务器名称: " + config.server.name);
            AtcInterConnectMod.LOGGER.info("API服务器: " + config.api.serverUrl);
            AtcInterConnectMod.LOGGER.info("WebSocket服务器: " + config.websocket.serverUrl);

        } catch (Exception e) {
            AtcInterConnectMod.LOGGER.error("加载配置失败", e);
            config = new Config(); // 使用默认配置
        }
    }

    private void saveDefaultConfig() {
        try {
            configFile.getParentFile().mkdirs();
            try (FileWriter writer = new FileWriter(configFile)) {
                gson.toJson(new Config(), writer);
            }
            AtcInterConnectMod.LOGGER.info("已创建默认配置文件: " + configFile.getAbsolutePath());
        } catch (IOException e) {
            AtcInterConnectMod.LOGGER.error("创建配置文件失败", e);
        }
    }

    // Getter方法
    public String getApiKey() {
        return config.server.apiKey;
    }

    public String getApiServerUrl() {
        return config.api.serverUrl;
    }

    public String getWebSocketUrl() {
        String wsUrl = config.websocket.serverUrl + config.websocket.endpoint + "?api_key=" + config.server.apiKey;
        return wsUrl;
    }

    public String getServerName() {
        return config.server.name;
    }

    public String getServerDescription() {
        return config.server.description;
    }

    public int getApiTimeout() {
        return config.api.timeout;
    }

    public int getReconnectInterval() {
        return config.websocket.reconnectInterval;
    }

    public int getPingInterval() {
        return config.websocket.pingInterval;
    }

    public boolean isEventEnabled(String eventType) {
        return config.events.getOrDefault(eventType, false);
    }

    public Map<String, Boolean> getEnabledEvents() {
        Map<String, Boolean> enabled = new HashMap<>();
        for (Map.Entry<String, Boolean> entry : config.events.entrySet()) {
            if (entry.getValue()) {
                enabled.put(entry.getKey(), true);
            }
        }
        return enabled;
    }

    // 配置类
    public static class Config {
        public Server server = new Server();
        public Api api = new Api();
        public WebSocket websocket = new WebSocket();
        public Map<String, Boolean> events = createDefaultEvents();

        public static class Server {
            @SerializedName("api_key")
            public String apiKey = "";

            public String name = "FabricServer";
            public String description = "Fabric服务器";
        }

        public static class Api {
            @SerializedName("server_url")
            public String serverUrl = "";

            public int timeout = 10;
        }

        public static class WebSocket {
            @SerializedName("server_url")
            public String serverUrl = "";

            public String endpoint = "/ws";

            @SerializedName("reconnect_interval")
            public int reconnectInterval = 5;

            @SerializedName("ping_interval")
            public int pingInterval = 30;
        }

        private static Map<String, Boolean> createDefaultEvents() {
            Map<String, Boolean> events = new HashMap<>();
            events.put("player_join", true);
            events.put("player_quit", true);
            events.put("player_chat", true);
            events.put("player_death", true);
            events.put("server_start", true);
            events.put("server_stop", true);
            return events;
        }
    }
}