package com.atc.interconnect;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ApiClient {
    private final String serverUrl;
    private final String apiKey;
    private final HttpClient httpClient;
    private final Gson gson;
    private final ExecutorService executor;

    public ApiClient(String serverUrl, String apiKey, int timeoutSeconds) {
        this.serverUrl = serverUrl.endsWith("/") ? serverUrl.substring(0, serverUrl.length() - 1) : serverUrl;
        this.apiKey = apiKey;
        this.gson = new Gson();
        this.executor = Executors.newFixedThreadPool(2);

        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(timeoutSeconds))
                .executor(executor)
                .build();
    }

    public CompletableFuture<Boolean> sendEvent(String eventType, String serverName, String message, Object data) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                JsonObject event = new JsonObject();
                event.addProperty("event_type", eventType);
                event.addProperty("server_name", serverName);
                event.addProperty("timestamp", Instant.now().toString());

                JsonObject eventData = new JsonObject();
                eventData.addProperty("message", message);
                if (data != null) {
                    eventData.add("details", gson.toJsonTree(data));
                }
                event.add("data", eventData);

                String requestBody = gson.toJson(event);
                AtcInterConnectMod.LOGGER.debug("发送事件请求: " + requestBody);

                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(serverUrl + "/api/events"))
                        .header("Content-Type", "application/json")
                        .header("Authorization", "Bearer " + apiKey)
                        .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                        .timeout(Duration.ofSeconds(10))
                        .build();

                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() == 200) {
                    return true;
                } else {
                    AtcInterConnectMod.LOGGER.warn("发送事件失败 - 状态码: " + response.statusCode() + ", 响应: " + response.body());
                    return false;
                }

            } catch (Exception e) {
                AtcInterConnectMod.LOGGER.error("发送事件异常: " + e.getMessage());
                return false;
            }
        }, executor);
    }

    public CompletableFuture<Boolean> healthCheck() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(serverUrl + "/health"))
                        .header("Authorization", "Bearer " + apiKey)
                        .GET()
                        .timeout(Duration.ofSeconds(5))
                        .build();

                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                return response.statusCode() == 200;

            } catch (Exception e) {
                AtcInterConnectMod.LOGGER.warn("健康检查失败: " + e.getMessage());
                return false;
            }
        }, executor);
    }

    public void shutdown() {
        try {
            if (!executor.isShutdown()) {
                executor.shutdown();
            }
        } catch (Exception e) {
            AtcInterConnectMod.LOGGER.warn("关闭API客户端时发生错误: " + e.getMessage());
        }
    }
}