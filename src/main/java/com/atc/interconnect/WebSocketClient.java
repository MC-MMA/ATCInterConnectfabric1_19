package com.atc.interconnect;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

public class WebSocketClient implements WebSocket.Listener {

    private final String url;
    private final String apiKey;
    private final AtcInterConnectMod mod;
    private final Gson gson;
    private final ScheduledExecutorService executor;
    private final AtomicReference<MinecraftServer> serverRef = new AtomicReference<>();

    private volatile WebSocket webSocket;
    private final AtomicBoolean connected = new AtomicBoolean(false);
    private final AtomicBoolean shouldReconnect = new AtomicBoolean(true);
    private final AtomicInteger reconnectAttempts = new AtomicInteger(0);
    private final StringBuilder messageBuffer = new StringBuilder();

    private static final int MAX_RECONNECT_ATTEMPTS = 10;

    // 用于检测消息中是否已经包含玩家名字的正则表达式
    private static final Pattern PLAYER_MESSAGE_PATTERN = Pattern.compile("^<([^>]+)>\\s*(.*)$");
    private static final Pattern PLAYER_COLON_PATTERN = Pattern.compile("^([^:]+):\\s*(.*)$");

    public WebSocketClient(String url, String apiKey, AtcInterConnectMod mod) {
        this.url = url;
        this.apiKey = apiKey;
        this.mod = mod;
        this.gson = new Gson();
        this.executor = Executors.newScheduledThreadPool(2);

        // 注册服务器事件以获取服务器实例
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            serverRef.set(server);
        });

        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            serverRef.set(null);
        });
    }

    public void connect() {
        if (connected.get()) return;

        AtcInterConnectMod.LOGGER.info("正在连接WebSocket服务器: " + url);

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .build();

        client.newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .buildAsync(URI.create(url), this)
                .thenAccept(ws -> {
                    this.webSocket = ws;
                    connected.set(true);
                    reconnectAttempts.set(0);
                    AtcInterConnectMod.LOGGER.info("WebSocket连接成功！");
                    startPingTask();
                })
                .exceptionally(throwable -> {
                    AtcInterConnectMod.LOGGER.error("WebSocket连接失败: " + throwable.getMessage());
                    scheduleReconnect();
                    return null;
                });
    }

    public void disconnect() {
        AtcInterConnectMod.LOGGER.info("正在断开WebSocket连接...");
        shouldReconnect.set(false);
        connected.set(false);

        if (webSocket != null) {
            try {
                webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "客户端关闭");
            } catch (Exception e) {
                AtcInterConnectMod.LOGGER.warn("关闭WebSocket时发生异常: " + e.getMessage());
            }
        }

        executor.shutdown();
    }

    @Override
    public void onOpen(WebSocket webSocket) {
        AtcInterConnectMod.LOGGER.info("WebSocket连接已建立");
        WebSocket.Listener.super.onOpen(webSocket);
    }

    @Override
    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
        messageBuffer.append(data);

        if (last) {
            String fullMessage = messageBuffer.toString().trim();
            messageBuffer.setLength(0);

            if (!fullMessage.isEmpty()) {
                // 增加原始消息日志
                AtcInterConnectMod.LOGGER.debug("收到WebSocket消息: " + fullMessage);
                processMessage(fullMessage);
            }
        }

        return WebSocket.Listener.super.onText(webSocket, data, last);
    }

    @Override
    public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
        AtcInterConnectMod.LOGGER.warn("WebSocket连接已关闭 - 状态码: " + statusCode + ", 原因: " + reason);
        connected.set(false);

        if (statusCode != WebSocket.NORMAL_CLOSURE && shouldReconnect.get()) {
            scheduleReconnect();
        }

        return WebSocket.Listener.super.onClose(webSocket, statusCode, reason);
    }

    @Override
    public void onError(WebSocket webSocket, Throwable error) {
        AtcInterConnectMod.LOGGER.error("WebSocket发生错误", error);
        connected.set(false);

        if (shouldReconnect.get()) {
            scheduleReconnect();
        }

        WebSocket.Listener.super.onError(webSocket, error);
    }

    private void processMessage(String message) {
        try {
            if (message.startsWith("{") && message.endsWith("}")) {
                JsonObject json = JsonParser.parseString(message).getAsJsonObject();
                handleJsonMessage(json);
            } else {
                handlePlainMessage(message);
            }
        } catch (Exception e) {
            AtcInterConnectMod.LOGGER.warn("处理消息时发生异常: " + message, e);
        }
    }

    private void handleJsonMessage(JsonObject message) {
        try {
            if (!message.has("type")) {
                AtcInterConnectMod.LOGGER.info("收到无type字段的消息: " + message);
                return;
            }

            String type = message.get("type").getAsString();
            AtcInterConnectMod.LOGGER.debug("收到消息类型: " + type);

            switch (type) {
                case "minecraft_event":
                    handleMinecraftEvent(message);
                    break;
                case "broadcast":
                    handleBroadcast(message);
                    break;
                case "pong":
                    handlePong(message);
                    break;
                default:
                    AtcInterConnectMod.LOGGER.info("收到未知类型消息: " + type);
                    break;
            }
        } catch (Exception e) {
            AtcInterConnectMod.LOGGER.warn("处理JSON消息时发生错误", e);
        }
    }

    private void handleMinecraftEvent(JsonObject message) {
        try {
            AtcInterConnectMod.LOGGER.debug("处理minecraft_event: " + message.toString());

            // Python服务器发送的格式: {"type": "minecraft_event", "event": {...}, "source_key_id_prefix": "..."}
            if (!message.has("event")) {
                AtcInterConnectMod.LOGGER.warn("minecraft_event消息缺少event字段");
                return;
            }

            JsonObject event = message.getAsJsonObject("event");
            String sourceKeyPrefix = message.has("source_key_id_prefix") ?
                    message.get("source_key_id_prefix").getAsString() : "unknown";

            AtcInterConnectMod.LOGGER.debug("事件源API密钥前缀: " + sourceKeyPrefix);
            AtcInterConnectMod.LOGGER.info("本地API密钥: " + apiKey.substring(0, Math.min(8, apiKey.length())) + "...");

            // 改进的自消息过滤逻辑 - 暂时禁用以便调试

            if (!event.has("event_type") || !event.has("server_name")) {
                AtcInterConnectMod.LOGGER.warn("事件缺少必要字段: event_type或server_name");
                AtcInterConnectMod.LOGGER.info("事件内容: " + event.toString());
                return;
            }

            String eventType = event.get("event_type").getAsString();
            String serverName = event.get("server_name").getAsString();

            boolean isOwnMsg = isSameServer(serverName);
            AtcInterConnectMod.LOGGER.debug("是否为自己的消息: " + isOwnMsg);

            if (isOwnMsg) {
                AtcInterConnectMod.LOGGER.info("忽略自己发送的事件");
                return;
            }
            AtcInterConnectMod.LOGGER.debug("事件类型: " + eventType + ", 来源服务器: " + serverName);

            // 处理不同类型的事件
            switch (eventType) {
                case "player_chat":
                    handleCrossServerChat(event, serverName);
                    break;
                case "player_join":
                case "player_quit":
                    handlePlayerEvent(event, serverName, eventType);
                    break;
                case "player_death":
                    handlePlayerDeath(event, serverName);
                    break;
                case "server_start":
                case "server_stop":
                    handleServerEvent(event, serverName, eventType);
                    break;
                default:
                    AtcInterConnectMod.LOGGER.info("未处理的事件类型: " + eventType);
                    break;
            }

        } catch (Exception e) {
            AtcInterConnectMod.LOGGER.warn("处理minecraft事件时发生错误", e);
        }
    }

    /**
     * 改进的自消息检测逻辑
     */
    private boolean isSameServer(String serverName) {
        String myServerName = mod.getConfigManager().getServerName();
        if (serverName == null)  {
            AtcInterConnectMod.LOGGER.warn("传入服务器名称为空");
            return false;
        }
        AtcInterConnectMod.LOGGER.debug("处理消息验证中，传入的服务器名称: " + serverName + ",本地服务器名称: " + myServerName);
        if (serverName.equals(myServerName)) {
            return true;
        }
        return false;
    }

    private void handleCrossServerChat(JsonObject event, String serverName) {
        try {
            AtcInterConnectMod.LOGGER.debug("处理跨服聊天事件: " + event.toString());

            JsonObject data = event.getAsJsonObject("data");
            if (data == null) {
                AtcInterConnectMod.LOGGER.warn("聊天事件缺少data字段");
                return;
            }

            String playerName = extractPlayerName(data);
            String rawMessage = extractChatMessage(data);

            AtcInterConnectMod.LOGGER.debug("提取的玩家名: " + playerName);
            AtcInterConnectMod.LOGGER.debug("提取的原始消息: " + rawMessage);

            if (playerName == null || rawMessage == null) {
                AtcInterConnectMod.LOGGER.warn("无法解析聊天消息的玩家名或消息内容");
                AtcInterConnectMod.LOGGER.warn("Data内容: " + data.toString());
                return;
            }

            // 清理消息内容，去除可能重复的玩家名字
            String cleanMessage = cleanChatMessage(rawMessage, playerName);

            AtcInterConnectMod.LOGGER.debug("原始消息: " + rawMessage);
            AtcInterConnectMod.LOGGER.debug("清理后消息: " + cleanMessage);

            // 构造跨服聊天消息
            Text message = Text.literal("[跨服] ")
                    .formatted(Formatting.AQUA)
                    .append(Text.literal("[" + serverName + "] ").formatted(Formatting.GRAY))
                    .append(Text.literal("<" + playerName + "> ").formatted(Formatting.WHITE))
                    .append(Text.literal(cleanMessage).formatted(Formatting.WHITE));

            // 广播给本服玩家
            broadcastToAllPlayers(message);
            AtcInterConnectMod.LOGGER.debug("已广播跨服聊天: [" + serverName + "] <" + playerName + "> " + cleanMessage);

        } catch (Exception e) {
            AtcInterConnectMod.LOGGER.warn("处理跨服聊天时发生错误", e);
        }
    }

    /**
     * 清理聊天消息，去除可能重复的玩家名字
     * 支持的格式:
     * - "<PlayerName> Hello" -> "Hello"
     * - "PlayerName: Hello" -> "Hello"
     * - "Hello" -> "Hello" (保持不变)
     */
    private String cleanChatMessage(String rawMessage, String playerName) {
        if (rawMessage == null || playerName == null) {
            return rawMessage;
        }

        // 检查 <PlayerName> 格式
        var matcher = PLAYER_MESSAGE_PATTERN.matcher(rawMessage);
        if (matcher.matches()) {
            String nameInMessage = matcher.group(1);
            String actualMessage = matcher.group(2);

            // 如果名字匹配，返回实际消息内容
            if (nameInMessage.equals(playerName)) {
                AtcInterConnectMod.LOGGER.info("检测到 <> 格式的重复玩家名: " + nameInMessage);
                return actualMessage;
            }
        }

        // 检查 PlayerName: 格式
        matcher = PLAYER_COLON_PATTERN.matcher(rawMessage);
        if (matcher.matches()) {
            String nameInMessage = matcher.group(1);
            String actualMessage = matcher.group(2);

            // 如果名字匹配，返回实际消息内容
            if (nameInMessage.equals(playerName)) {
                AtcInterConnectMod.LOGGER.info("检测到 : 格式的重复玩家名: " + nameInMessage);
                return actualMessage;
            }
        }

        // 如果没有匹配到任何格式，返回原消息
        return rawMessage;
    }

    private void handlePlayerEvent(JsonObject event, String serverName, String eventType) {
        try {
            JsonObject data = event.getAsJsonObject("data");
            if (data == null) return;

            String playerName = extractPlayerName(data);
            if (playerName == null) {
                AtcInterConnectMod.LOGGER.info("无法提取玩家名称，Data: " + data.toString());
                return;
            }

            String action = "player_join".equals(eventType) ? "加入了" : "离开了";
            Formatting color = "player_join".equals(eventType) ? Formatting.GREEN : Formatting.RED;

            Text message = Text.literal("[跨服] ")
                    .formatted(Formatting.GRAY)
                    .append(Text.literal(playerName).formatted(Formatting.WHITE))
                    .append(Text.literal(" " + action + "服务器 ").formatted(color))
                    .append(Text.literal(serverName).formatted(Formatting.YELLOW));

            // 广播给本服玩家
            broadcastToAllPlayers(message);
            AtcInterConnectMod.LOGGER.info("已广播玩家事件: " + playerName + " " + action + " " + serverName);

        } catch (Exception e) {
            AtcInterConnectMod.LOGGER.warn("处理玩家事件时发生错误", e);
        }
    }

    private void handlePlayerDeath(JsonObject event, String serverName) {
        try {
            JsonObject data = event.getAsJsonObject("data");
            if (data == null) return;

            String playerName = extractPlayerName(data);
            if (playerName == null) return;

            Text message = Text.literal("[跨服] ")
                    .formatted(Formatting.DARK_RED)
                    .append(Text.literal("[" + serverName + "] ").formatted(Formatting.GRAY))
                    .append(Text.literal(playerName + " 死亡了").formatted(Formatting.RED));

            // 广播给本服玩家
            broadcastToAllPlayers(message);
            AtcInterConnectMod.LOGGER.info("已广播死亡事件: [" + serverName + "] " + playerName + " 死亡了");

        } catch (Exception e) {
            AtcInterConnectMod.LOGGER.warn("处理玩家死亡事件时发生错误", e);
        }
    }

    private void handleServerEvent(JsonObject event, String serverName, String eventType) {
        try {
            String action = "server_start".equals(eventType) ? "已启动" : "已关闭";
            Formatting color = "server_start".equals(eventType) ? Formatting.GREEN : Formatting.RED;

            Text message = Text.literal("[跨服] ")
                    .formatted(Formatting.GRAY)
                    .append(Text.literal("服务器 ").formatted(Formatting.WHITE))
                    .append(Text.literal(serverName).formatted(Formatting.YELLOW))
                    .append(Text.literal(" " + action).formatted(color));

            // 广播给本服玩家
            broadcastToAllPlayers(message);
            AtcInterConnectMod.LOGGER.info("已广播服务器事件: " + serverName + " " + action);

        } catch (Exception e) {
            AtcInterConnectMod.LOGGER.warn("处理服务器事件时发生错误", e);
        }
    }

    /**
     * 提取玩家名称 - 支持多种数据格式
     */
    private String extractPlayerName(JsonObject data) {
        // 直接格式
        if (data.has("player")) {
            return data.get("player").getAsString();
        }

        // 嵌套格式
        if (data.has("details")) {
            JsonObject details = data.getAsJsonObject("details");
            if (details.has("player")) {
                return details.get("player").getAsString();
            }
        }

        return null;
    }

    /**
     * 提取聊天消息 - 支持多种数据格式
     */
    private String extractChatMessage(JsonObject data) {
        // 直接格式
        if (data.has("message")) {
            return data.get("message").getAsString();
        }

        // 嵌套格式
        if (data.has("details")) {
            JsonObject details = data.getAsJsonObject("details");
            if (details.has("message")) {
                return details.get("message").getAsString();
            }
        }

        return null;
    }

    private void handleBroadcast(JsonObject message) {
        try {
            if (!message.has("message")) return;

            String broadcastMessage = message.get("message").getAsString();
            Text text = Text.literal("[广播] " + broadcastMessage).formatted(Formatting.YELLOW);

            // 广播给本服玩家
            broadcastToAllPlayers(text);
            AtcInterConnectMod.LOGGER.info("已广播管理员消息: " + broadcastMessage);

        } catch (Exception e) {
            AtcInterConnectMod.LOGGER.warn("处理广播消息时发生错误", e);
        }
    }

    private void handlePong(JsonObject message) {
        AtcInterConnectMod.LOGGER.debug("收到pong响应");
    }

    private void handlePlainMessage(String message) {
        // 处理纯文本消息
        AtcInterConnectMod.LOGGER.debug("收到纯文本消息: " + message);
    }

    private void broadcastToAllPlayers(Text message) {
        try {
            MinecraftServer server = serverRef.get();

            if (server != null) {
                int playerCount = 0;
                for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
                    player.sendMessage(message, false);
                    playerCount++;
                }
                AtcInterConnectMod.LOGGER.debug("消息已发送给 " + playerCount + " 个玩家");
            } else {
                AtcInterConnectMod.LOGGER.warn("服务器实例为null，无法广播消息");
            }
        } catch (Exception e) {
            AtcInterConnectMod.LOGGER.warn("广播消息时发生错误", e);
        }
    }

    private void startPingTask() {
        executor.scheduleAtFixedRate(() -> {
            if (connected.get() && webSocket != null) {
                JsonObject ping = new JsonObject();
                ping.addProperty("type", "ping");
                ping.addProperty("timestamp", System.currentTimeMillis());

                try {
                    webSocket.sendText(gson.toJson(ping), true);
                    AtcInterConnectMod.LOGGER.debug("发送心跳包");
                } catch (Exception e) {
                    AtcInterConnectMod.LOGGER.warn("发送心跳失败", e);
                }
            }
        }, 30, 30, TimeUnit.SECONDS);
    }

    private void scheduleReconnect() {
        if (!shouldReconnect.get()) return;

        int attempts = reconnectAttempts.incrementAndGet();
        if (attempts > MAX_RECONNECT_ATTEMPTS) {
            AtcInterConnectMod.LOGGER.error("达到最大重连次数，停止重连");
            return;
        }

        int delay = Math.min(5 * attempts, 60); // 最大延迟60秒
        AtcInterConnectMod.LOGGER.info("计划在 " + delay + " 秒后进行第 " + attempts + " 次重连");

        executor.schedule(this::connect, delay, TimeUnit.SECONDS);
    }

    public void sendMessage(String message) {
        if (connected.get() && webSocket != null) {
            try {
                webSocket.sendText(message, true);
            } catch (Exception e) {
                AtcInterConnectMod.LOGGER.warn("发送消息失败: " + message, e);
            }
        }
    }

    public boolean isConnected() {
        return connected.get();
    }

    public boolean reconnect() {
        if (connected.get()) disconnect();
        connect();
        return true;
    }

    public String getConnectionStatus() {
        if (connected.get()) {
            return "已连接";
        } else if (reconnectAttempts.get() > 0) {
            return "重连中 (尝试次数: " + reconnectAttempts.get() + "/" + MAX_RECONNECT_ATTEMPTS + ")";
        } else {
            return "已断开";
        }
    }
}