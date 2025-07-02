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
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicLong;
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
    private final AtomicBoolean connecting = new AtomicBoolean(false);
    private final AtomicInteger reconnectAttempts = new AtomicInteger(0);
    private final StringBuilder messageBuffer = new StringBuilder();
    private final AtomicLong lastPongTime = new AtomicLong(System.currentTimeMillis());

    private ScheduledFuture<?> pingTask;
    private ScheduledFuture<?> connectionCheckTask;

    private static final int MAX_RECONNECT_ATTEMPTS = 15;
    private static final int PING_INTERVAL = 25; // 25秒发送一次ping
    private static final int CONNECTION_TIMEOUT = 60; // 60秒无响应则认为连接断开
    private static final int INITIAL_RECONNECT_DELAY = 3; // 初始重连延迟3秒

    // 用于检测消息中是否已经包含玩家名字的正则表达式
    private static final Pattern PLAYER_MESSAGE_PATTERN = Pattern.compile("^<([^>]+)>\\s*(.*)$");
    private static final Pattern PLAYER_COLON_PATTERN = Pattern.compile("^([^:]+):\\s*(.*)$");

    public WebSocketClient(String url, String apiKey, AtcInterConnectMod mod) {
        this.url = url;
        this.apiKey = apiKey;
        this.mod = mod;
        this.gson = new Gson();
        this.executor = Executors.newScheduledThreadPool(3); // 增加线程池大小

        // 注册服务器事件以获取服务器实例
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            serverRef.set(server);
        });

        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            serverRef.set(null);
        });
    }

    public void connect() {
        if (connected.get() || connecting.get()) {
            AtcInterConnectMod.LOGGER.debug("WebSocket已连接或正在连接中，跳过连接请求");
            return;
        }

        connecting.set(true);
        AtcInterConnectMod.LOGGER.info("正在连接WebSocket服务器: " + url);

        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(20))
                    .executor(executor)
                    .build();

            client.newWebSocketBuilder()
                    .connectTimeout(Duration.ofSeconds(25))
                    .buildAsync(URI.create(url), this)
                    .whenComplete((ws, throwable) -> {
                        connecting.set(false);
                        if (throwable != null) {
                            AtcInterConnectMod.LOGGER.error("WebSocket连接失败: " + throwable.getMessage());
                            connected.set(false);
                            scheduleReconnect();
                        } else {
                            this.webSocket = ws;
                            connected.set(true);
                            reconnectAttempts.set(0);
                            lastPongTime.set(System.currentTimeMillis());
                            AtcInterConnectMod.LOGGER.info("WebSocket连接成功！");
                            startPingTask();
                            startConnectionCheckTask();
                        }
                    });
        } catch (Exception e) {
            connecting.set(false);
            AtcInterConnectMod.LOGGER.error("创建WebSocket连接时发生异常", e);
            scheduleReconnect();
        }
    }

    public void disconnect() {
        AtcInterConnectMod.LOGGER.info("正在断开WebSocket连接...");
        shouldReconnect.set(false);
        connected.set(false);
        connecting.set(false);

        // 停止定时任务
        stopTasks();

        if (webSocket != null) {
            try {
                webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "客户端关闭")
                        .orTimeout(5, TimeUnit.SECONDS)
                        .exceptionally(throwable -> {
                            AtcInterConnectMod.LOGGER.warn("关闭WebSocket时发生异常: " + throwable.getMessage());
                            return null;
                        });
            } catch (Exception e) {
                AtcInterConnectMod.LOGGER.warn("关闭WebSocket时发生异常: " + e.getMessage());
            }
            webSocket = null;
        }

        // 关闭线程池
        if (!executor.isShutdown()) {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }

    private void stopTasks() {
        if (pingTask != null && !pingTask.isCancelled()) {
            pingTask.cancel(false);
            pingTask = null;
        }
        if (connectionCheckTask != null && !connectionCheckTask.isCancelled()) {
            connectionCheckTask.cancel(false);
            connectionCheckTask = null;
        }
    }

    @Override
    public void onOpen(WebSocket webSocket) {
        AtcInterConnectMod.LOGGER.info("WebSocket连接已建立");
        // 发送认证消息
        try {
            JsonObject auth = new JsonObject();
            auth.addProperty("type", "auth");
            auth.addProperty("server_name", mod.getConfigManager().getServerName());
            webSocket.sendText(gson.toJson(auth), true);
        } catch (Exception e) {
            AtcInterConnectMod.LOGGER.warn("发送认证消息失败", e);
        }
        WebSocket.Listener.super.onOpen(webSocket);
    }

    @Override
    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
        try {
            messageBuffer.append(data);

            if (last) {
                String fullMessage = messageBuffer.toString().trim();
                messageBuffer.setLength(0);

                if (!fullMessage.isEmpty()) {
                    AtcInterConnectMod.LOGGER.debug("收到WebSocket消息: " + fullMessage);
                    processMessage(fullMessage);
                }
            }

            // 请求更多数据
            webSocket.request(1);
        } catch (Exception e) {
            AtcInterConnectMod.LOGGER.warn("处理WebSocket消息时发生异常", e);
        }

        return WebSocket.Listener.super.onText(webSocket, data, last);
    }

    @Override
    public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
        AtcInterConnectMod.LOGGER.warn("WebSocket连接已关闭 - 状态码: " + statusCode + ", 原因: " + reason);
        connected.set(false);
        connecting.set(false);
        stopTasks();

        if (statusCode != WebSocket.NORMAL_CLOSURE && shouldReconnect.get()) {
            scheduleReconnect();
        }

        return WebSocket.Listener.super.onClose(webSocket, statusCode, reason);
    }

    @Override
    public void onError(WebSocket webSocket, Throwable error) {
        AtcInterConnectMod.LOGGER.error("WebSocket发生错误: " + error.getMessage());
        connected.set(false);
        connecting.set(false);
        stopTasks();

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
                AtcInterConnectMod.LOGGER.debug("收到无type字段的消息: " + message);
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
                case "auth_success":
                    AtcInterConnectMod.LOGGER.info("WebSocket认证成功");
                    break;
                case "auth_failed":
                    AtcInterConnectMod.LOGGER.error("WebSocket认证失败");
                    break;
                default:
                    AtcInterConnectMod.LOGGER.debug("收到未知类型消息: " + type);
                    break;
            }
        } catch (Exception e) {
            AtcInterConnectMod.LOGGER.warn("处理JSON消息时发生错误", e);
        }
    }

    private void handleMinecraftEvent(JsonObject message) {
        try {
            AtcInterConnectMod.LOGGER.debug("处理minecraft_event: " + message.toString());

            if (!message.has("event")) {
                AtcInterConnectMod.LOGGER.warn("minecraft_event消息缺少event字段");
                return;
            }

            JsonObject event = message.getAsJsonObject("event");

            if (!event.has("event_type") || !event.has("server_name")) {
                AtcInterConnectMod.LOGGER.warn("事件缺少必要字段: event_type或server_name");
                return;
            }

            String eventType = event.get("event_type").getAsString();
            String serverName = event.get("server_name").getAsString();

            // 检查是否为自己发送的消息
            if (isSameServer(serverName)) {
                AtcInterConnectMod.LOGGER.debug("忽略自己发送的事件: " + eventType);
                return;
            }

            AtcInterConnectMod.LOGGER.debug("处理事件类型: " + eventType + ", 来源服务器: " + serverName);

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
                    AtcInterConnectMod.LOGGER.debug("未处理的事件类型: " + eventType);
                    break;
            }

        } catch (Exception e) {
            AtcInterConnectMod.LOGGER.warn("处理minecraft事件时发生错误", e);
        }
    }

    private boolean isSameServer(String serverName) {
        String myServerName = mod.getConfigManager().getServerName();
        if (serverName == null || myServerName == null) {
            return false;
        }
        return serverName.equals(myServerName);
    }

    private void handleCrossServerChat(JsonObject event, String serverName) {
        try {
            JsonObject data = event.getAsJsonObject("data");
            if (data == null) {
                AtcInterConnectMod.LOGGER.warn("聊天事件缺少data字段");
                return;
            }

            String playerName = extractPlayerName(data);
            String rawMessage = extractChatMessage(data);

            if (playerName == null || rawMessage == null) {
                AtcInterConnectMod.LOGGER.warn("无法解析聊天消息的玩家名或消息内容");
                return;
            }

            String cleanMessage = cleanChatMessage(rawMessage, playerName);

            Text message = Text.literal("[跨服] ")
                    .formatted(Formatting.AQUA)
                    .append(Text.literal("[" + serverName + "] ").formatted(Formatting.GRAY))
                    .append(Text.literal("<" + playerName + "> ").formatted(Formatting.WHITE))
                    .append(Text.literal(cleanMessage).formatted(Formatting.WHITE));

            broadcastToAllPlayers(message);
            AtcInterConnectMod.LOGGER.debug("已广播跨服聊天: [" + serverName + "] <" + playerName + "> " + cleanMessage);

        } catch (Exception e) {
            AtcInterConnectMod.LOGGER.warn("处理跨服聊天时发生错误", e);
        }
    }

    private String cleanChatMessage(String rawMessage, String playerName) {
        if (rawMessage == null || playerName == null) {
            return rawMessage;
        }

        var matcher = PLAYER_MESSAGE_PATTERN.matcher(rawMessage);
        if (matcher.matches()) {
            String nameInMessage = matcher.group(1);
            String actualMessage = matcher.group(2);
            if (nameInMessage.equals(playerName)) {
                return actualMessage;
            }
        }

        matcher = PLAYER_COLON_PATTERN.matcher(rawMessage);
        if (matcher.matches()) {
            String nameInMessage = matcher.group(1);
            String actualMessage = matcher.group(2);
            if (nameInMessage.equals(playerName)) {
                return actualMessage;
            }
        }

        return rawMessage;
    }

    private void handlePlayerEvent(JsonObject event, String serverName, String eventType) {
        try {
            JsonObject data = event.getAsJsonObject("data");
            if (data == null) return;

            String playerName = extractPlayerName(data);
            if (playerName == null) return;

            String action = "player_join".equals(eventType) ? "加入了" : "离开了";
            Formatting color = "player_join".equals(eventType) ? Formatting.GREEN : Formatting.RED;

            Text message = Text.literal("[跨服] ")
                    .formatted(Formatting.GRAY)
                    .append(Text.literal(playerName).formatted(Formatting.WHITE))
                    .append(Text.literal(" " + action + "服务器 ").formatted(color))
                    .append(Text.literal(serverName).formatted(Formatting.YELLOW));

            broadcastToAllPlayers(message);

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

            broadcastToAllPlayers(message);

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

            broadcastToAllPlayers(message);

        } catch (Exception e) {
            AtcInterConnectMod.LOGGER.warn("处理服务器事件时发生错误", e);
        }
    }

    private String extractPlayerName(JsonObject data) {
        if (data.has("player")) {
            return data.get("player").getAsString();
        }
        if (data.has("details")) {
            JsonObject details = data.getAsJsonObject("details");
            if (details.has("player")) {
                return details.get("player").getAsString();
            }
        }
        return null;
    }

    private String extractChatMessage(JsonObject data) {
        if (data.has("message")) {
            return data.get("message").getAsString();
        }
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

            broadcastToAllPlayers(text);

        } catch (Exception e) {
            AtcInterConnectMod.LOGGER.warn("处理广播消息时发生错误", e);
        }
    }

    private void handlePong(JsonObject message) {
        lastPongTime.set(System.currentTimeMillis());
        AtcInterConnectMod.LOGGER.debug("收到pong响应，更新最后响应时间");
    }

    private void handlePlainMessage(String message) {
        AtcInterConnectMod.LOGGER.debug("收到纯文本消息: " + message);
    }

    private void broadcastToAllPlayers(Text message) {
        try {
            MinecraftServer server = serverRef.get();
            if (server != null) {
                server.execute(() -> {
                    try {
                        int playerCount = 0;
                        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
                            player.sendMessage(message, false);
                            playerCount++;
                        }
                        AtcInterConnectMod.LOGGER.debug("消息已发送给 " + playerCount + " 个玩家");
                    } catch (Exception e) {
                        AtcInterConnectMod.LOGGER.warn("广播消息时发生错误", e);
                    }
                });
            } else {
                AtcInterConnectMod.LOGGER.warn("服务器实例为null，无法广播消息");
            }
        } catch (Exception e) {
            AtcInterConnectMod.LOGGER.warn("广播消息时发生错误", e);
        }
    }

    private void startPingTask() {
        if (pingTask != null && !pingTask.isCancelled()) {
            pingTask.cancel(false);
        }

        pingTask = executor.scheduleAtFixedRate(() -> {
            if (connected.get() && webSocket != null) {
                try {
                    JsonObject ping = new JsonObject();
                    ping.addProperty("type", "ping");
                    ping.addProperty("timestamp", System.currentTimeMillis());

                    webSocket.sendText(gson.toJson(ping), true)
                            .orTimeout(10, TimeUnit.SECONDS)
                            .exceptionally(throwable -> {
                                AtcInterConnectMod.LOGGER.warn("发送心跳失败: " + throwable.getMessage());
                                return null;
                            });

                    AtcInterConnectMod.LOGGER.debug("发送心跳包");
                } catch (Exception e) {
                    AtcInterConnectMod.LOGGER.warn("发送心跳失败", e);
                }
            }
        }, PING_INTERVAL, PING_INTERVAL, TimeUnit.SECONDS);
    }

    private void startConnectionCheckTask() {
        if (connectionCheckTask != null && !connectionCheckTask.isCancelled()) {
            connectionCheckTask.cancel(false);
        }

        connectionCheckTask = executor.scheduleAtFixedRate(() -> {
            if (connected.get()) {
                long timeSinceLastPong = System.currentTimeMillis() - lastPongTime.get();
                if (timeSinceLastPong > CONNECTION_TIMEOUT * 1000) {
                    AtcInterConnectMod.LOGGER.warn("连接超时，超过 " + CONNECTION_TIMEOUT + " 秒未收到响应，准备重连");
                    connected.set(false);
                    if (webSocket != null) {
                        try {
                            webSocket.abort();
                        } catch (Exception e) {
                            AtcInterConnectMod.LOGGER.debug("中断WebSocket连接时发生异常: " + e.getMessage());
                        }
                    }
                    scheduleReconnect();
                }
            }
        }, CONNECTION_TIMEOUT, CONNECTION_TIMEOUT / 2, TimeUnit.SECONDS);
    }

    private void scheduleReconnect() {
        if (!shouldReconnect.get() || connecting.get()) return;

        int attempts = reconnectAttempts.incrementAndGet();
        if (attempts > MAX_RECONNECT_ATTEMPTS) {
            AtcInterConnectMod.LOGGER.error("达到最大重连次数 (" + MAX_RECONNECT_ATTEMPTS + ")，停止重连");
            return;
        }

        // 指数退避算法，但限制最大延迟
        int delay = Math.min(INITIAL_RECONNECT_DELAY * (int)Math.pow(2, Math.min(attempts - 1, 6)), 120);
        AtcInterConnectMod.LOGGER.info("计划在 " + delay + " 秒后进行第 " + attempts + " 次重连");

        executor.schedule(() -> {
            if (shouldReconnect.get() && !connected.get() && !connecting.get()) {
                connect();
            }
        }, delay, TimeUnit.SECONDS);
    }

    public void sendMessage(String message) {
        if (connected.get() && webSocket != null) {
            try {
                webSocket.sendText(message, true)
                        .orTimeout(10, TimeUnit.SECONDS)
                        .exceptionally(throwable -> {
                            AtcInterConnectMod.LOGGER.warn("发送消息失败: " + message + ", 错误: " + throwable.getMessage());
                            return null;
                        });
            } catch (Exception e) {
                AtcInterConnectMod.LOGGER.warn("发送消息失败: " + message, e);
            }
        } else {
            AtcInterConnectMod.LOGGER.warn("WebSocket未连接，无法发送消息: " + message);
        }
    }

    public boolean isConnected() {
        return connected.get();
    }

    public boolean reconnect() {
        if (connected.get()) {
            AtcInterConnectMod.LOGGER.info("断开当前连接以进行重连");
            connected.set(false);
            if (webSocket != null) {
                try {
                    webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "手动重连");
                } catch (Exception e) {
                    AtcInterConnectMod.LOGGER.debug("关闭连接时发生异常: " + e.getMessage());
                }
            }
        }

        reconnectAttempts.set(0); // 重置重连计数
        connect();
        return true;
    }

    public String getConnectionStatus() {
        if (connecting.get()) {
            return "连接中...";
        } else if (connected.get()) {
            return "已连接";
        } else if (reconnectAttempts.get() > 0) {
            return "重连中 (尝试次数: " + reconnectAttempts.get() + "/" + MAX_RECONNECT_ATTEMPTS + ")";
        } else {
            return "已断开";
        }
    }
}