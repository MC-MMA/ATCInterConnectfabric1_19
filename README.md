# ATC InterConnect Fabric

一个用于Minecraft Fabric的跨服务器通信模组，通过WebSocket连接实现多服务器间的实时玩家事件和聊天同步。

*A cross-server communication mod for Minecraft Fabric, enabling real-time player events and chat synchronization across multiple servers via WebSocket connections.*

---

## ✨ 功能特性 Features

### 🌐 跨服务器通信 Cross-Server Communication
- **实时聊天同步** - Real-time chat synchronization across servers
- **玩家事件广播** - Player events broadcasting (join/quit/death)
- **服务器状态通知** - Server status notifications (start/stop)

### 🔗 双重连接模式 Dual Connection Mode
- **WebSocket实时连接** - WebSocket for real-time communication
- **HTTP API事件上报** - HTTP API for event reporting
- **自动重连机制** - Automatic reconnection mechanism

### ⚙️ 灵活配置 Flexible Configuration
- **事件选择性启用** - Selective event enabling
- **服务器信息定制** - Customizable server information
- **连接参数调节** - Adjustable connection parameters

### 🎮 游戏内管理 In-Game Management
- **管理员命令** - Administrator commands
- **实时状态查看** - Real-time status monitoring
- **配置热重载** - Hot configuration reloading

---

## 📝 待办事项 TO DO

### 🎯 版本兼容性 Version Compatibility
- [ ] **支持Minecraft 1.21.x** - *Support for Minecraft 1.21.x*

### 🌏 本地化改进 Localization Improvements
- [ ] **支持中文服务器名称及描述** - *Support for Chinese server names and descriptions*

---

## 🚀 快速开始 Quick Start

### 📋 系统要求 Requirements

- **Minecraft**: 1.19 - 1.19.4
- **Fabric Loader**: ≥ 0.14.0
- **Fabric API**: ≥ 0.58.0
- **Java**: ≥ 17
- **服务端**: 需要配套的WebSocket服务器

### 📦 安装范例 Installation（Example）

1. **下载mod文件** - Download the mod file
   ```
   atcinterconnectfabric-2.0.1.jar
   ```

2. **放置到mods目录** - Place in mods directory
   ```
   /mods/atcinterconnectfabric-2.0.1.jar
   ```

3. **启动服务器** - Start the server
   ```bash
   java -jar fabric-server-launch.jar
   ```

4. **配置连接信息** - Configure connection settings
   ```
   /config/atc-interconnect.json
   ```

---

## ⚙️ 配置 Configuration

### 📝 配置文件 Config File

首次启动后，会在 `/config/` 目录下生成 `atc-interconnect.json` 配置文件：

*After first startup, a config file `atc-interconnect.json` will be generated in the `/config/` directory:*

```json
{
  "server": {
    "api_key": "your-api-key-here",
    "name": "FabricServer",
    "description": "Fabric服务器"
  },
  "api": {
    "server_url": "http://your-api-server.com",
    "timeout": 10
  },
  "websocket": {
    "server_url": "ws://your-websocket-server.com",
    "endpoint": "/ws",
    "reconnect_interval": 5,
    "ping_interval": 30
  },
  "events": {
    "player_join": true,
    "player_quit": true,
    "player_chat": true,
    "player_death": true,
    "server_start": true,
    "server_stop": true
  }
}
```

### 🔧 配置说明 Configuration Details

| 字段 Field | 说明 Description | 默认值 Default |
|------------|------------------|----------------|
| `server.api_key` | API认证密钥 *API authentication key* | `""` |
| `server.name` | 服务器名称 *Server name* | `"FabricServer"` |
| `server.description` | 服务器描述 *Server description* | `"Fabric服务器"` |
| `api.server_url` | API服务器地址 *API server URL* | `""` |
| `api.timeout` | API超时时间(秒) *API timeout (seconds)* | `10` |
| `websocket.server_url` | WebSocket服务器地址 *WebSocket server URL* | `""` |
| `websocket.endpoint` | WebSocket端点 *WebSocket endpoint* | `"/ws"` |
| `websocket.reconnect_interval` | 重连间隔(秒) *Reconnect interval (seconds)* | `5` |
| `websocket.ping_interval` | 心跳间隔(秒) *Ping interval (seconds)* | `30` |

### 📊 事件配置 Event Configuration

可以通过 `events` 部分控制哪些事件会被同步：

*You can control which events are synchronized through the `events` section:*

| 事件类型 Event Type | 说明 Description |
|-------------------|------------------|
| `player_join` | 玩家加入服务器 *Player joins server* |
| `player_quit` | 玩家离开服务器 *Player leaves server* |
| `player_chat` | 玩家聊天消息 *Player chat messages* |
| `player_death` | 玩家死亡事件 *Player death events* |
| `server_start` | 服务器启动 *Server startup* |
| `server_stop` | 服务器关闭 *Server shutdown* |

---

## 🎮 游戏内命令 In-Game Commands

所有命令需要OP权限 (权限等级2) - *All commands require OP permissions (permission level 2)*

### 📋 命令列表 Command List

```bash
# 显示帮助信息 - Show help information
/atcinterconnect

# 查看连接状态 - Check connection status  
/atcinterconnect status

# 重载配置文件 - Reload configuration
/atcinterconnect reload

# 重新连接WebSocket - Reconnect WebSocket
/atcinterconnect reconnect

# 发送测试事件 - Send test event
/atcinterconnect test <eventType>
/atcinterconnect test <eventType> <message>
```

### 📖 命令示例 Command Examples

```bash
# 查看详细状态信息
/atcinterconnect status

# 重载配置（修改配置文件后）
/atcinterconnect reload

# 测试玩家加入事件
/atcinterconnect test player_join

# 测试自定义消息
/atcinterconnect test broadcast "服务器维护通知"
```

---

## 🔌 API接口 API Interface

### 📤 事件上报 Event Reporting

模组会向配置的API服务器发送HTTP POST请求：

*The mod sends HTTP POST requests to the configured API server:*

**端点 Endpoint**: `POST /api/events`

**请求头 Headers**:
```http
Content-Type: application/json
Authorization: Bearer <api_key>
```

**请求体 Request Body**:
```json
{
  "event_type": "player_chat",
  "server_name": "FabricServer",
  "timestamp": "2025-06-15T10:30:00Z",
  "data": {
    "message": "Hello world!",
    "details": {
      "player": "PlayerName",
      "uuid": "550e8400-e29b-41d4-a716-446655440000",
      "message": "Hello world!"
    }
  }
}
```

### 🔄 WebSocket通信 WebSocket Communication

**连接URL**: `ws://server-url/ws?api_key=<api_key>`

**消息格式 Message Format**:
```json
{
  "type": "minecraft_event",
  "event": {
    "event_type": "player_join",
    "server_name": "FabricServer",
    "data": {
      "player": "PlayerName",
      "uuid": "550e8400-e29b-41d4-a716-446655440000"
    }
  }
}
```

---

## 📋 事件数据结构 Event Data Structure

### 👤 玩家事件 Player Events

#### player_join / player_quit
```json
{
  "player": "PlayerName",
  "uuid": "550e8400-e29b-41d4-a716-446655440000",
  "display_name": "PlayerName",
  "first_join": false,
  "player_count": 5
}
```

#### player_chat
```json
{
  "player": "PlayerName", 
  "uuid": "550e8400-e29b-41d4-a716-446655440000",
  "message": "Hello everyone!"
}
```

#### player_death
```json
{
  "player": "PlayerName",
  "uuid": "550e8400-e29b-41d4-a716-446655440000",
  "location": {
    "world": "minecraft:overworld",
    "x": 100.5,
    "y": 64.0,
    "z": -200.3
  }
}
```

### 🖥️ 服务器事件 Server Events

#### server_start / server_stop
```json
{
  "message": "服务器已启动"
}
```

---

## 🛠️ 开发信息 Development Info

### 📁 项目结构 Project Structure

```
src/main/java/com/atc/interconnect/
├── AtcInterConnectMod.java      # 主模组类 Main mod class
├── ApiClient.java               # HTTP API客户端 HTTP API client  
├── WebSocketClient.java         # WebSocket客户端 WebSocket client
├── ConfigManager.java           # 配置管理器 Configuration manager
├── EventListener.java           # 事件监听器 Event listener
└── Commands.java                # 命令处理器 Command handler
```

### 🔧 构建 Building

```bash
# 克隆项目 - Clone project
git clone <repository-url>
cd atc-interconnect-fabric

# 构建mod - Build mod
./gradlew build

# 输出文件位置 - Output location
build/libs/atcinterconnectfabric-2.0.1.jar
```

### 📚 依赖项 Dependencies

- **Fabric API**: 核心Fabric模组API *Core Fabric mod API*
- **Gson**: JSON序列化/反序列化 *JSON serialization/deserialization*
- **Java HTTP Client**: HTTP和WebSocket通信 *HTTP and WebSocket communication*
- **SLF4J**: 日志记录 *Logging*

---

## 🔍 故障排除 Troubleshooting

### ❌ 常见问题 Common Issues

#### 1. WebSocket连接失败
**问题**: WebSocket connection failed
**解决方案 Solution**:
- 检查 `websocket.server_url` 配置 *Check websocket.server_url config*
- 确认API密钥正确 *Verify API key is correct*
- 检查网络连接 *Check network connectivity*

#### 2. API请求超时
**问题**: API request timeout  
**解决方案 Solution**:
- 增加 `api.timeout` 值 *Increase api.timeout value*
- 检查API服务器状态 *Check API server status*
- 确认 `api.server_url` 可访问 *Verify api.server_url is accessible*

#### 3. 事件不同步
**问题**: Events not synchronizing
**解决方案 Solution**:
- 检查事件配置是否启用 *Check if events are enabled in config*
- 查看服务器日志错误信息 *Check server logs for errors*
- 使用 `/atcinterconnect test` 测试连接 *Use test command to verify connection*

### 📊 日志级别 Log Levels

```properties
# 在server.properties中设置日志级别
# Set log level in server.properties
level=DEBUG  # 调试模式 Debug mode
level=INFO   # 常规模式 Normal mode  
level=WARN   # 警告模式 Warning mode
```

---

## 🤝 贡献 Contributing

欢迎贡献代码和建议！*Contributions and suggestions are welcome!*

### 📋 贡献指南 Contribution Guidelines

1. **Fork** 项目仓库 *Fork the repository*
2. **创建** 功能分支 *Create a feature branch*
3. **提交** 更改 *Commit your changes*
4. **推送** 到分支 *Push to the branch*
5. **创建** Pull Request *Create a Pull Request*

### 🐛 问题报告 Bug Reports

请提供以下信息 *Please provide the following information*:

- Minecraft版本 *Minecraft version*
- Fabric Loader版本 *Fabric Loader version*
- 模组版本 *Mod version*
- 错误日志 *Error logs*
- 重现步骤 *Steps to reproduce*

---

## 📄 许可证 License

本项目采用 GPL-3.0 许可证 - 查看 [LICENSE](LICENSE) 文件了解详情。

*This project is licensed under the GPL-3.0 License - see the [LICENSE](LICENSE) file for details.*

---

## 👨‍💻 作者 Author

**BeiChen** - *Initial work*

---

## 🙏 致谢 Acknowledgments

- **Fabric团队** - 提供优秀的模组开发框架 *For the excellent modding framework*
- **Minecraft社区** - 持续的支持和反馈 *For continuous support and feedback*
- **开源贡献者** - 代码贡献和建议 *For code contributions and suggestions*

---

## 📞 支持 Support

如果您遇到问题或需要帮助，请：

*If you encounter issues or need help, please:*

- 📧 发送邮件 *Send email*: admin@furcraft.top
- 🐛 报告问题 *Report issues*: [GitHub Issues]

---

**⭐ 如果这个项目对您有帮助，请给一个Star！**

***If this project helps you, please give it a star!***
