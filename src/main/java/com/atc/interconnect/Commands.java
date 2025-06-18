package com.atc.interconnect;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.command.CommandRegistryAccess;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.Map;

public class Commands {

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher, CommandRegistryAccess registryAccess, CommandManager.RegistrationEnvironment environment) {
        dispatcher.register(
                CommandManager.literal("atcinterconnect")
                        .requires(source -> source.hasPermissionLevel(2))
                        .then(CommandManager.literal("status")
                                .executes(Commands::handleStatus))
                        .then(CommandManager.literal("reload")
                                .executes(Commands::handleReload))
                        .then(CommandManager.literal("reconnect")
                                .executes(Commands::handleReconnect))
                        .then(CommandManager.literal("test")
                                .then(CommandManager.argument("eventType", StringArgumentType.word())
                                        .executes(Commands::handleTest)
                                        .then(CommandManager.argument("message", StringArgumentType.greedyString())
                                                .executes(Commands::handleTestWithMessage))))
                        .executes(Commands::handleHelp)
        );
    }

    private static int handleHelp(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();

        source.sendFeedback(Text.literal("=== ATC InterConnect 命令帮助 ===").formatted(Formatting.AQUA), false);
        source.sendFeedback(Text.literal("/atcinterconnect status - 查看连接状态").formatted(Formatting.WHITE), false);
        source.sendFeedback(Text.literal("/atcinterconnect reload - 重载配置").formatted(Formatting.WHITE), false);
        source.sendFeedback(Text.literal("/atcinterconnect reconnect - 重新连接").formatted(Formatting.WHITE), false);
        source.sendFeedback(Text.literal("/atcinterconnect test <事件> [消息] - 发送测试事件").formatted(Formatting.WHITE), false);

        return 1;
    }

    private static int handleStatus(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();
        AtcInterConnectMod mod = AtcInterConnectMod.getInstance();

        if (mod == null) {
            source.sendError(Text.literal("Mod实例未找到"));
            return 0;
        }

        source.sendFeedback(Text.literal("=== ATC InterConnect 状态 ===").formatted(Formatting.AQUA), false);
        source.sendFeedback(Text.literal("服务器名称: " + mod.getConfigManager().getServerName()).formatted(Formatting.GREEN), false);
        source.sendFeedback(Text.literal("API服务器: " + mod.getConfigManager().getApiServerUrl()).formatted(Formatting.GREEN), false);

        String wsStatus = mod.getWebSocketClient().isConnected() ? "已连接" : "未连接";
        Formatting wsColor = mod.getWebSocketClient().isConnected() ? Formatting.GREEN : Formatting.RED;
        source.sendFeedback(Text.literal("WebSocket状态: " + wsStatus).formatted(wsColor), false);

        // 显示启用的事件
        Map<String, Boolean> enabledEvents = mod.getConfigManager().getEnabledEvents();
        source.sendFeedback(Text.literal("已启用事件 (" + enabledEvents.size() + "): " + String.join(", ", enabledEvents.keySet())).formatted(Formatting.YELLOW), false);

        // API连接测试
        mod.getApiClient().healthCheck().thenAccept(healthy -> {
            String apiStatus = healthy ? "正常" : "异常";
            Formatting apiColor = healthy ? Formatting.GREEN : Formatting.RED;
            source.sendFeedback(Text.literal("API连接: " + apiStatus).formatted(apiColor), false);
        });

        return 1;
    }

    private static int handleReload(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();
        AtcInterConnectMod mod = AtcInterConnectMod.getInstance();

        if (mod == null) {
            source.sendError(Text.literal("Mod实例未找到"));
            return 0;
        }

        source.sendFeedback(Text.literal("正在重载配置...").formatted(Formatting.GREEN), false);

        try {
            mod.reloadConfig();
            source.sendFeedback(Text.literal("配置重载成功！").formatted(Formatting.GREEN), false);
        } catch (Exception e) {
            source.sendError(Text.literal("配置重载失败: " + e.getMessage()));
            return 0;
        }

        return 1;
    }

    private static int handleReconnect(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();
        AtcInterConnectMod mod = AtcInterConnectMod.getInstance();

        if (mod == null) {
            source.sendError(Text.literal("Mod实例未找到"));
            return 0;
        }

        source.sendFeedback(Text.literal("正在重新连接WebSocket...").formatted(Formatting.GREEN), false);

        if (mod.reconnectToServer()) {
            source.sendFeedback(Text.literal("重连请求已发送").formatted(Formatting.GREEN), false);
        } else {
            source.sendError(Text.literal("重连失败"));
            return 0;
        }

        return 1;
    }

    private static int handleTest(CommandContext<ServerCommandSource> context) {
        String eventType = StringArgumentType.getString(context, "eventType");
        return sendTestEvent(context, eventType, "测试事件 - " + eventType);
    }

    private static int handleTestWithMessage(CommandContext<ServerCommandSource> context) {
        String eventType = StringArgumentType.getString(context, "eventType");
        String message = StringArgumentType.getString(context, "message");
        return sendTestEvent(context, eventType, message);
    }

    private static int sendTestEvent(CommandContext<ServerCommandSource> context, String eventType, String message) {
        ServerCommandSource source = context.getSource();
        AtcInterConnectMod mod = AtcInterConnectMod.getInstance();

        if (mod == null) {
            source.sendError(Text.literal("Mod实例未找到"));
            return 0;
        }

        source.sendFeedback(Text.literal("正在发送测试事件: " + eventType).formatted(Formatting.GREEN), false);

        Map<String, Object> testData = Map.of(
                "test", true,
                "sender", source.getName(),
                "timestamp", System.currentTimeMillis()
        );

        mod.sendServerEvent(eventType, message, testData);
        source.sendFeedback(Text.literal("测试事件已发送！").formatted(Formatting.GREEN), false);

        return 1;
    }
}