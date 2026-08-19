# QQ 机器人中转站 API 开发文档

## 1. 概述

中转站（`QQBotProxy`）是连接 QQ 开放平台与下游业务系统的中间层，只负责消息的**监听**与**发送**，不处理业务逻辑。

业务逻辑由下游服务（外部项目）完成。下游服务通过 **WebSocket** 连接中转站：

- 中转站把收到的群聊 / 私聊消息推送（`User` 对象）给所有已连接的下游服务
- 下游服务通过发送指令，让中转站把消息发到 QQ 群或私聊用户
- 支持多个下游服务同时连接

## 2. 架构

```
QQ群 / 私聊  ⇄  QQ开放平台  ⇄  中转站(QQBotProxy)  ⇄  下游服务A (WebSocket客户端)
                                                      ⇄  下游服务B (WebSocket客户端)
                                                      ⇄  下游服务C (WebSocket客户端)
```

## 3. 连接方式

### 3.1 连接地址

```
ws://<中转站服务器IP>:<端口>
```

默认端口：`18080`

本地调试：

```
ws://127.0.0.1:18080
```

### 3.2 协议

- 使用 **WebSocket** 长连接
- 所有消息均为 **JSON** 文本
- 编码：UTF-8

## 4. 安全认证

中转站与下游服务之间通过**共享密钥**进行认证，参考 Velocity 的 `forwarding-secret-file` 机制。

### 4.1 密钥文件

- 文件名：`forwarding.secret`
- 内容：16 位字母 + 数字字符串
- 位置：与中转站 / 下游服务的配置文件同级

### 4.2 认证方式

下游服务在建立 WebSocket 连接时，必须在握手 HTTP Header 中携带：

```
X-Forwarding-Secret: <forwarding.secret 内容>
```

中转站在握手阶段校验该值，与本地 `forwarding.secret` 内容完全一致才允许连接，否则返回关闭码 `1008` 拒绝连接。

### 4.3 Java 客户端示例

```java
Map<String, String> headers = new HashMap<>();
headers.put("X-Forwarding-Secret", secret);

WebSocketClient client = new WebSocketClient(new URI(url), headers) {
    // ...
};
client.connectBlocking();
```

## 5. 消息推送（中转站 → 下游服务）

### 5.1 群聊消息

```json
{
  "type": "group_message",
  "data": {
    "group_nickname": "ttwlwlbb51522",
    "openid": "BA643FBDA83A19D3E307FCC467BA06D3",
    "is_group_owner": true,
    "is_group_admin": true,
    "is_bot_admin": false,
    "message": "/point abcde12345",
    "group_openid": "39ECC870B08E7E16111F66567803803E",
    "msg_id": "ROBOT1.0_xxx",
    "msg_seq": null,
    "timestamp": 1750000000000
  }
}
```

### 5.2 私聊消息

```json
{
  "type": "c2c_message",
  "data": {
    "group_nickname": "ttwlwlbb51522",
    "openid": "BA643FBDA83A19D3E307FCC467BA06D3",
    "is_group_owner": false,
    "is_group_admin": false,
    "is_bot_admin": false,
    "message": "你好",
    "group_openid": null,
    "msg_id": "xxx",
    "msg_seq": null,
    "timestamp": 1750000000000
  }
}
```

> 私聊消息的 `group_openid` 为 `null`，用户标识为 `openid`。

## 6. 发送指令（下游服务 → 中转站）

### 6.1 发送群聊消息

```json
{
  "type": "send_group_message",
  "group_openid": "39ECC870B08E7E16111F66567803803E",
  "content": "这是群聊消息内容",
  "msg_id": "可选，原消息id",
  "msg_seq": 0
}
```

| 字段             | 必填 | 说明                           |
|----------------|----|------------------------------|
| `type`         | 是  | 固定为 `send_group_message`     |
| `group_openid` | 是  | 目标群 openid                   |
| `content`      | 是  | 消息内容                         |
| `msg_id`       | 否  | 被动回复时填原消息 id                 |
| `msg_seq`      | 否  | 被动回复时填原消息序号，需与 `msg_id` 同时存在 |

### 6.2 发送私聊消息

```json
{
  "type": "send_c2c_message",
  "user_openid": "BA643FBDA83A19D3E307FCC467BA06D3",
  "content": "这是私聊消息内容",
  "msg_id": "可选，原消息id",
  "msg_seq": 0
}
```

| 字段            | 必填 | 说明                           |
|---------------|----|------------------------------|
| `type`        | 是  | 固定为 `send_c2c_message`       |
| `user_openid` | 是  | 目标用户 openid                  |
| `content`     | 是  | 消息内容                         |
| `msg_id`      | 否  | 被动回复时填原消息 id                 |
| `msg_seq`     | 否  | 被动回复时填原消息序号，需与 `msg_id` 同时存在 |

## 7. 响应（中转站 → 下游服务）

下游服务发送指令后，中转站会返回一条响应。

### 7.1 成功

```json
{
  "type": "ack",
  "message": "已提交发送"
}
```

### 7.2 失败

```json
{
  "type": "error",
  "message": "group_openid 和 content 不能为空"
}
```

## 8. @提及转 openid 规则

中转站在推送前，会把消息中的「@用户」自动转换为该用户的 `openid`。

| 群里原文              | 推送给下游的 `message`     |
|-------------------|----------------------|
| `/point @张三`      | `/point abcde12345`  |
| `@机器人 /point @张三` | `/point abcde12345`  |
| `/point @李四`      | `/point <李四的openid>` |

说明：

- `@普通用户` → 替换为该用户的 `openid`
- `@机器人` → 直接移除（机器人是被调用方，不是目标）
- 该转换依赖事件中的 `mentions` 数组，只有实际 `@` 了某人才能转换

## 9. User 对象字段说明

`User` 类为 Java 封装类，序列化为 JSON 时使用下划线命名。

| JSON 字段          | Java 字段         | 类型      | 说明                            |
|------------------|-----------------|---------|-------------------------------|
| `group_nickname` | `groupNickname` | String  | 群昵称                           |
| `openid`         | `openid`        | String  | 发送者 openid（用户唯一标识）            |
| `is_group_owner` | `groupOwner`    | boolean | 是否群主                          |
| `is_group_admin` | `groupAdmin`    | boolean | 是否管理员（含群主）                    |
| `is_bot_admin`   | `botAdmin`      | boolean | 是否机器人管理员（由中转站 `admin_ids` 判断） |
| `message`        | `message`       | String  | 消息内容（@用户名已转为 openid）          |
| `group_openid`   | `groupOpenid`   | String  | 群 openid（群聊有值，私聊为 null）       |
| `msg_id`         | `msgId`         | String  | 消息 id                         |
| `msg_seq`        | `msgSeq`        | Long    | 消息序号（可能为 null）                |
| `timestamp`      | `timestamp`     | long    | 时间戳（毫秒）                       |

## 10. 中转站配置

中转站使用 `config.json`，与 jar 包同级，首次运行自动生成默认文件。

```json
{
  "app_id": "你的AppID",
  "app_secret": "你的AppSecret",
  "intents": 33554432,
  "external_port": 18080,
  "admin_ids": [
    "你的管理员openid或id"
  ],
  "secret_file": "forwarding.secret"
}
```

| 字段              | 说明                                   |
|-----------------|--------------------------------------|
| `app_id`        | 机器人 AppID                            |
| `app_secret`    | 机器人 AppSecret                        |
| `intents`       | 事件权限位，群聊/单聊默认 `33554432`（`1 << 25`）  |
| `external_port` | 对外 WebSocket 服务端口                    |
| `admin_ids`     | 机器人管理员 openid 列表，用于判断 `is_bot_admin` |
| `secret_file`   | 共享密钥文件名，默认 `forwarding.secret`       |

运行方式：

```bash
java -jar QQBotProxy-1.0.0.jar                    # 默认读 jar 同级 config.json
java -jar QQBotProxy-1.0.0.jar /path/config.json  # 指定配置文件
```

## 11. 下游服务配置

下游服务的 `config.json`：

```json
{
  "server_url": "ws://127.0.0.1:18080",
  "secret_file": "forwarding.secret"
}
```

| 字段            | 说明                 |
|---------------|--------------------|
| `server_url`  | 中转站 WebSocket 地址   |
| `secret_file` | 共享密钥文件名，内容必须与中转站一致 |

## 12. 下游服务接入示例（Java）

```java
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;

public class ExternalProject {
    public static void main(String[] args) throws Exception {
        String secret = "Ab3dEf9gH2JkL4mN"; // 与中转站 forwarding.secret 一致

        Map<String, String> headers = new HashMap<>();
        headers.put("X-Forwarding-Secret", secret);

        WebSocketClient client = new WebSocketClient(new URI("ws://127.0.0.1:18080"), headers) {
            @Override
            public void onOpen(ServerHandshake handshake) {
                System.out.println("已连接中转站");
            }

            @Override
            public void onMessage(String message) {
                System.out.println("收到消息: " + message);
                // 自行解析 JSON 处理
            }

            @Override
            public void onClose(int code, String reason, boolean remote) {
                System.out.println("连接关闭");
            }

            @Override
            public void onError(Exception ex) {
                System.out.println("连接错误: " + ex.getMessage());
            }
        };

        client.connectBlocking();

        // 发送群聊消息
        client.send("{\"type\":\"send_group_message\",\"group_openid\":\"xxx\",\"content\":\"你好\"}");
        // 发送私聊消息
        client.send("{\"type\":\"send_c2c_message\",\"user_openid\":\"xxx\",\"content\":\"你好\"}");
    }
}
```

## 13. 测试项目命令说明

下游测试项目连接中转站后，在控制台使用以下命令：

| 命令                               | 说明          |
|----------------------------------|-------------|
| `send group <group_openid> <内容>` | 发送群聊消息      |
| `send user <user_openid> <内容>`   | 发送私聊消息      |
| `reply group <内容>`               | 回复最近收到消息的群  |
| `reply user <内容>`                | 回复最近收到私聊的用户 |
| `exit`                           | 退出          |

## 14. 部署说明

### 14.1 部署目录结构

```
中转站目录/
├── QQBotProxy-1.0.0.jar
├── config.json
└── forwarding.secret        ← 首次运行自动生成

下游服务目录/
├── qqbot-test-1.0.0.jar
├── config.json
└── forwarding.secret        ← 从中转站复制，内容必须一致
```

### 14.2 部署顺序

1. 启动中转站，首次运行自动生成 `forwarding.secret`
2. 将中转站目录下的 `forwarding.secret` 复制到下游服务目录
3. 启动下游服务

## 15. 注意事项

1. **用户标识**：QQ 官方接口不返回真实 QQ 号，用户唯一标识统一使用 `openid`。
2. **机器人管理员**：通过中转站 `admin_ids` 配置的 openid 列表判断。
3. **私聊限制**：机器人主动私聊前，用户需要先给机器人发过消息（建立会话）。
4. **能力开通**：群聊、单聊能力需在 QQ 开放平台申请开通。
5. **IP 白名单**：中转站所在服务器的公网 IP 需加入开放平台白名单。
6. **多下游服务**：所有通过密钥校验、连接中转站的下游服务，都会收到相同的消息推送。
7. **密钥一致性**：两份 `forwarding.secret` 内容必须逐字节一致，否则连接被拒。
8. **密钥安全**：`forwarding.secret` 文件不要提交到公开仓库、不要泄露。
9. **@转换依赖**：`@用户` 转 openid 依赖事件中的 `mentions` 数组，只有实际 `@` 了某人才能转换。