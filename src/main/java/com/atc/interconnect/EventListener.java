package com.atc.interconnect;

import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.fabric.api.networking.v1.PacketSender;
import net.minecraft.network.message.MessageType;
import net.minecraft.network.message.SignedMessage;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.network.ServerPlayNetworkHandler;

import java.util.HashMap;
import java.util.Map;

public class EventListener {
    private final AtcInterConnectMod mod;

    public EventListener(AtcInterConnectMod mod) {
        this.mod = mod;
        registerEvents();
    }

    private void registerEvents() {
        // 玩家死亡事件
        ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> {
            if (!alive && mod.getConfigManager().isEventEnabled("player_death")) {
                handlePlayerDeath(newPlayer);
            }
        });

        // 玩家聊天事件 - 1.19.x版本使用不同的签名
        ServerMessageEvents.CHAT_MESSAGE.register((message, sender, typeKey) -> {
            if (mod.getConfigManager().isEventEnabled("player_chat")) {
                handlePlayerChat(sender, message);
            }
        });
    }

    // 正确的玩家加入事件监听器签名
    public void onPlayerJoin(ServerPlayNetworkHandler handler, PacketSender sender, MinecraftServer server) {
        ServerPlayerEntity player = handler.getPlayer();
        if (player != null && mod.getConfigManager().isEventEnabled("player_join")) {
            handlePlayerJoin(player);
        }
    }

    // 正确的玩家断开连接事件监听器签名
    public void onPlayerDisconnect(ServerPlayNetworkHandler handler, MinecraftServer server) {
        ServerPlayerEntity player = handler.getPlayer();
        if (player != null && mod.getConfigManager().isEventEnabled("player_quit")) {
            handlePlayerQuit(player);
        }
    }

    private void handlePlayerJoin(ServerPlayerEntity player) {
        try {
            Map<String, Object> data = new HashMap<>();
            data.put("player", player.getGameProfile().getName());
            data.put("uuid", player.getUuid().toString());
            data.put("display_name", player.getDisplayName().getString());

            // 1.19.x版本的统计检查方法
            boolean isFirstJoin = player.getStatHandler().getStat(net.minecraft.stat.Stats.CUSTOM.getOrCreateStat(net.minecraft.stat.Stats.PLAY_TIME)) == 0;
            data.put("first_join", isFirstJoin);
            data.put("player_count", player.getServer().getCurrentPlayerCount());

            // 发送玩家加入事件
            mod.sendServerEvent("player_join", player.getGameProfile().getName() + " 加入了服务器", data);

        } catch (Exception e) {
            AtcInterConnectMod.LOGGER.warn("处理玩家加入事件时发生错误", e);
        }
    }

    private void handlePlayerQuit(ServerPlayerEntity player) {
        try {
            Map<String, Object> data = new HashMap<>();
            data.put("player", player.getGameProfile().getName());
            data.put("uuid", player.getUuid().toString());
            data.put("player_count", Math.max(0, player.getServer().getCurrentPlayerCount() - 1));

            // 发送玩家离开事件
            mod.sendServerEvent("player_quit", player.getGameProfile().getName() + " 离开了服务器", data);

        } catch (Exception e) {
            AtcInterConnectMod.LOGGER.warn("处理玩家退出事件时发生错误", e);
        }
    }

    private void handlePlayerChat(ServerPlayerEntity player, SignedMessage message) {
        try {
            String chatContent = message.getContent().getString();
            String playerName = player.getGameProfile().getName();

            Map<String, Object> data = new HashMap<>();
            data.put("player", playerName);
            data.put("uuid", player.getUuid().toString());
            data.put("message", chatContent);

            // 将实际的聊天内容作为消息发送
            mod.sendServerEvent("player_chat", chatContent, data);

            AtcInterConnectMod.LOGGER.debug("发送聊天事件: " + playerName + " -> " + chatContent);

        } catch (Exception e) {
            AtcInterConnectMod.LOGGER.warn("处理玩家聊天事件时发生错误", e);
        }
    }

    private void handlePlayerDeath(ServerPlayerEntity player) {
        try {
            Map<String, Object> data = new HashMap<>();
            data.put("player", player.getGameProfile().getName());
            data.put("uuid", player.getUuid().toString());

            // 获取死亡位置
            data.put("location", Map.of(
                    "world", player.getWorld().getRegistryKey().getValue().toString(),
                    "x", player.getX(),
                    "y", player.getY(),
                    "z", player.getZ()
            ));

            // 发送玩家死亡事件
            mod.sendServerEvent("player_death", player.getGameProfile().getName() + " 死亡了", data);

        } catch (Exception e) {
            AtcInterConnectMod.LOGGER.warn("处理玩家死亡事件时发生错误", e);
        }
    }
}