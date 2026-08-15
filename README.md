# QQ 机器人中转站 API 文档

## 1. 概述

中转站（`QQBot`）只负责与 QQ 开放平台通信，完成消息的**监听**与**发送**，不处理业务逻辑。

业务逻辑由外部 Java 项目完成。外部项目通过 **WebSocket** 连接中转站：

- 中转站把收到的群聊 / 私聊消息推送（`User` 对象）给所有已连接的外部项目
- 外部项目通过发送指令，让中转站把消息发到 QQ 群或私聊用户
- 支持多个外部项目同时连接

---

## 2. 架构

```
QQ群 / 私聊  ⇄  QQ开放平台  ⇄  中转站(QQBot)  ⇄  外部项目A (WebSocket客户端)
                                                ⇄  外部项目B (WebSocket客户端)
                                                ⇄  外部项目C (WebSocket客户端)
```

---

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

---

## 4. 消息推送（中转站 → 外部项目）

### 4.1 群聊消息

```json
{
  "type": "group_message",
  "data": {
    "group_nickname": "ttwlwlbb51522",
    "openid": "BA643FBDA83A19D3E307FCC467BA06D3",
    "is_group_owner": true,
    "is_group_admin": true,
    "is_bot_admin": false,
    "message": "你好",
    "group_openid": "39ECC870B08E7E16111F66567803803E",
    "msg_id": "ROBOT1.0_xxx",
    "msg_seq": null,
    "timestamp": 1750000000000
  }
}
```

### 4.2 私聊消息

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

---

## 5. 发送指令（外部项目 → 中转站）

### 5.1 发送群聊消息

```json
{
  "type": "send_group_message",
  "group_openid": "39ECC870B08E7E16111F66567803803E",
  "content": "这是群聊消息内容",
  "msg_id": "可选，原消息id",
  "msg_seq": 可选，原消息序号
}
```

| 字段 | 必填 | 说明 |
|---|---|---|
| `type` | 是 | 固定为 `send_group_message` |
| `group_openid` | 是 | 目标群 openid |
| `content` | 是 | 消息内容 |
| `msg_id` | 否 | 被动回复时填原消息 id |
| `msg_seq` | 否 | 被动回复时填原消息序号，需与 `msg_id` 同时存在 |

### 5.2 发送私聊消息

```json
{
  "type": "send_c2c_message",
  "user_openid": "BA643FBDA83A19D3E307FCC467BA06D3",
  "content": "这是私聊消息内容",
  "msg_id": "可选，原消息id",
  "msg_seq": 可选，原消息序号
}
```

| 字段 | 必填 | 说明 |
|---|---|---|
| `type` | 是 | 固定为 `send_c2c_message` |
| `user_openid` | 是 | 目标用户 openid |
| `content` | 是 | 消息内容 |
| `msg_id` | 否 | 被动回复时填原消息 id |
| `msg_seq` | 否 | 被动回复时填原消息序号，需与 `msg_id` 同时存在 |

---

## 6. 响应（中转站 → 外部项目）

外部项目发送指令后，中转站会返回一条响应：

### 6.1 成功

```json
{
  "type": "ack",
  "message": "已提交发送"
}
```

### 6.2 失败

```json
{
  "type": "error",
  "message": "group_openid 和 content 不能为空"
}
```

---

## 7. User 对象字段说明

`User` 类为 Java 封装类，序列化为 JSON 时使用下划线命名。

| JSON 字段 | Java 字段 | 类型 | 说明 |
|---|---|---|---|
| `group_nickname` | `groupNickname` | String | 群昵称 |
| `openid` | `openid` | String | 发送者 openid（用户唯一标识） |
| `is_group_owner` | `groupOwner` | boolean | 是否群主 |
| `is_group_admin` | `groupAdmin` | boolean | 是否管理员（含群主） |
| `is_bot_admin` | `botAdmin` | boolean | 是否机器人管理员（由中转站 `admin_ids` 判断） |
| `message` | `message` | String | 消息内容（已去除 @机器人标记） |
| `group_openid` | `groupOpenid` | String | 群 openid（群聊有值，私聊为 null） |
| `msg_id` | `msgId` | String | 消息 id |
| `msg_seq` | `msgSeq` | Long | 消息序号（可能为 null）