package cn.citprobe.proxy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.java_websocket.WebSocket;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.handshake.ServerHandshake;
import org.java_websocket.server.WebSocketServer;

import java.io.File;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;

public class QQBotProxy {

    private final String appId;
    private final String appSecret;
    private final long intents;
    private final Set<String> adminIds;   // 机器人管理员 openid/id 集合
    private final int externalPort;       // 对外 WebSocket 服务端口

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newHttpClient();

    // access_token 缓存
    private String accessToken;
    private long tokenExpireAt;

    // 官方 WebSocket 连接
    private WebSocketClient ws;
    private String gatewayUrl;
    private Long lastSeq;
    private long heartbeatInterval = 30;
    private Thread heartbeatThread;

    // 消息去重
    private final Set<String> processedMsgIds = ConcurrentHashMap.newKeySet();

    // 对外服务
    private ExternalApiServer externalServer;

    // ==================== 入口 ====================

    public static void main(String[] args) {
        String configPath;

        if (args.length > 0) {
            // 命令行指定了配置文件路径
            configPath = args[0];
        } else {
            // 默认：与 jar 包同级的 config.json
            configPath = getJarDir() + File.separator + "config.json";
        }

        Config config = Config.load(configPath);
        if (config == null) {
            return;
        }

        QQBotProxy bot = new QQBotProxy(
                config.getAppId(),
                config.getAppSecret(),
                config.getIntents(),
                config.getAdminIds(),
                config.getExternalPort()
        );
        bot.run();
    }

    /**
     * 获取 jar 包所在目录。
     * 打包运行：返回 jar 所在目录
     * IDEA 直接运行：返回 target/classes 的父目录（target）
     */
    private static String getJarDir() {
        try {
            String location = QQBotProxy.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI().getPath();
            File f = new File(location);
            return f.getParentFile().getAbsolutePath();
        } catch (Exception e) {
            return new File(".").getAbsolutePath();
        }
    }

    public QQBotProxy(String appId, String appSecret, long intents, Set<String> adminIds, int externalPort) {
        this.appId = appId;
        this.appSecret = appSecret;
        this.intents = intents;
        this.adminIds = adminIds;
        this.externalPort = externalPort;
    }

    // ==================== QQ 官方鉴权 ====================

    private void fetchAccessToken() throws Exception {
        String body = String.format("{\"appId\":\"%s\",\"clientSecret\":\"%s\"}", appId, appSecret);
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("https://bots.qq.com/app/getAppAccessToken"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        JsonNode data = mapper.readTree(resp.body());

        if (!data.has("access_token")) {
            throw new RuntimeException("获取 access_token 失败: " + resp.body());
        }

        accessToken = data.get("access_token").asText();
        long expiresIn = parseLong(data.get("expires_in"), 7200);
        tokenExpireAt = System.currentTimeMillis() / 1000 + expiresIn - 300;
        System.out.println("access_token 获取成功");
    }

    private synchronized String getAccessToken() throws Exception {
        if (accessToken == null || System.currentTimeMillis() / 1000 >= tokenExpireAt) {
            fetchAccessToken();
        }
        return accessToken;
    }

    private String getGateway() throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("https://api.sgroup.qq.com/gateway"))
                .header("Authorization", "QQBot " + getAccessToken())
                .GET()
                .build();
        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        JsonNode data = mapper.readTree(resp.body());

        if (!data.has("url")) {
            throw new RuntimeException("获取网关失败: " + resp.body());
        }
        return data.get("url").asText();
    }

    // ==================== 官方 WebSocket 连接 ====================

    private void connect() throws Exception {
        gatewayUrl = getGateway();
        System.out.println("连接 QQ 网关: " + gatewayUrl);

        final CountDownLatch closeLatch = new CountDownLatch(1);

        ws = new WebSocketClient(new URI(gatewayUrl)) {
            @Override
            public void onOpen(ServerHandshake handshake) {
                System.out.println("QQ WebSocket 已连接");
            }

            @Override
            public void onMessage(String message) {
                QQBotProxy.this.handleWsMessage(message);
            }

            @Override
            public void onClose(int code, String reason, boolean remote) {
                System.out.println("QQ WebSocket 关闭: " + code + " " + reason + " remote=" + remote);
                closeLatch.countDown();
            }

            @Override
            public void onError(Exception ex) {
                System.out.println("QQ WebSocket 错误: " + ex.getMessage());
                closeLatch.countDown();
            }
        };

        ws.connectBlocking();   // 等待连接建立
        closeLatch.await();     // 阻塞到连接关闭
    }

    private void handleWsMessage(String message) {
        try {
            JsonNode data = mapper.readTree(message);
            int op = data.path("op").asInt(-1);

            if (op == 10) { // Hello
                long intervalMs = parseLong(data.path("d").get("heartbeat_interval"), 30000);
                heartbeatInterval = Math.max(1, intervalMs / 1000);
                lastSeq = null;
                System.out.println("收到 Hello，心跳间隔: " + heartbeatInterval + " 秒");
                startHeartbeat();
                sendIdentify();

            } else if (op == 11) { // 心跳确认，忽略
            } else if (op == 0) {  // Dispatch
                JsonNode s = data.get("s");
                if (s != null && s.isNumber()) {
                    lastSeq = s.asLong();
                }
                handleDispatch(data);

            } else if (op == 7) {  // 要求重连
                System.out.println("服务端要求重连");
                if (ws != null) ws.close();

            } else if (op == 9) {  // 鉴权失败
                System.out.println("鉴权失败或 session 无效");
            }
        } catch (Exception e) {
            System.out.println("处理 QQ 消息失败: " + e.getMessage());
        }
    }

    private void sendIdentify() throws Exception {
        ObjectNode d = mapper.createObjectNode();
        d.put("token", "QQBot " + getAccessToken());
        d.put("intents", intents);

        ArrayNode shard = mapper.createArrayNode();
        shard.add(0);
        shard.add(1);
        d.set("shard", shard);

        ObjectNode payload = mapper.createObjectNode();
        payload.put("op", 2);
        payload.set("d", d);

        ws.send(mapper.writeValueAsString(payload));
        System.out.println("已发送鉴权信息");
    }

    private void startHeartbeat() {
        if (heartbeatThread != null && heartbeatThread.isAlive()) {
            return;
        }

        heartbeatThread = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(heartbeatInterval * 1000);
                    if (ws != null && ws.isOpen()) {
                        ObjectNode payload = mapper.createObjectNode();
                        payload.put("op", 1);
                        if (lastSeq != null) {
                            payload.put("d", lastSeq.longValue());
                        } else {
                            payload.putNull("d");
                        }
                        ws.send(mapper.writeValueAsString(payload));
                    } else {
                        break;
                    }
                } catch (Exception e) {
                    System.out.println("心跳发送失败: " + e.getMessage());
                    break;
                }
            }
        });
        heartbeatThread.setDaemon(true);
        heartbeatThread.start();
    }

    // ==================== 事件分发 ====================

    private void handleDispatch(JsonNode event) {
        String t = event.path("t").asText("");
        JsonNode d = event.path("d");

        if ("READY".equals(t)) {
            System.out.println("鉴权成功，机器人已上线");
            return;
        }

        if ("MESSAGE_CREATE".equals(t)
                || "AT_MESSAGE_CREATE".equals(t)
                || "GROUP_AT_MESSAGE_CREATE".equals(t)
                || "GROUP_MESSAGE_CREATE".equals(t)
                || "C2C_MESSAGE_CREATE".equals(t)) {
            handleMessage(t, d);
        }
    }

    private void handleMessage(String t, JsonNode d) {
        // 跳过机器人自己
        if (d.path("author").path("bot").asBoolean(false)) {
            return;
        }

        String msgId = d.has("id") ? d.get("id").asText() : null;
        if (msgId != null && !processedMsgIds.add(msgId)) {
            return;
        }

        try {
            User user = buildUser(d);

            boolean isC2C = "C2C_MESSAGE_CREATE".equals(t);

            ObjectNode envelope = mapper.createObjectNode();
            envelope.put("type", isC2C ? "c2c_message" : "group_message");
            envelope.set("data", mapper.valueToTree(user));

            externalServer.broadcast(mapper.writeValueAsString(envelope));
            System.out.println("已推送" + (isC2C ? "私聊" : "群") + "消息: " + user.getMessage());
        } catch (Exception e) {
            System.out.println("推送消息失败: " + e.getMessage());
        }
    }


    private User buildUser(JsonNode d) {
        JsonNode author = d.path("author");

        User u = new User();
        u.setGroupNickname(author.path("username").asText(""));

        String role = author.path("member_role").asText("");
        u.setGroupOwner("owner".equals(role));
        u.setGroupAdmin("owner".equals(role) || "administrator".equals(role));

        String uid = author.has("id") ? author.get("id").asText() : "";
        String memberOpenid = author.path("member_openid").asText("");

        u.setOpenid(memberOpenid.isEmpty() ? uid : memberOpenid);
        u.setBotAdmin(adminIds.contains(uid) || adminIds.contains(u.getOpenid()));

        u.setMessage(cleanContent(d.path("content").asText("")));
        u.setGroupOpenid(d.path("group_openid").asText(null));
        u.setMsgId(d.has("id") ? d.get("id").asText() : null);
        u.setMsgSeq((d.has("msg_seq") && d.get("msg_seq").isNumber()) ? d.get("msg_seq").asLong() : null);
        u.setTimestamp(System.currentTimeMillis());
        return u;
    }


    private String cleanContent(String content) {
        content = content.strip();
        content = content.replaceAll("<@!?[^>]*>", "").strip();

        String[] parts = content.split("\\s+", 2);
        if (parts.length > 0 && parts[0].startsWith("@")) {
            content = parts.length > 1 ? parts[1] : "";
        }
        return content.strip();
    }

    // ==================== 对外：发送消息到 QQ 群 ====================

    /**
     * 向 QQ 群发送消息。
     * 外部项目通过 WebSocket 指令或直接调用此方法使用。
     */
    public void sendGroupMessage(String groupOpenid, String content, String msgId, Long msgSeq) {
        ObjectNode body = mapper.createObjectNode();
        body.put("content", content);
        body.put("msg_type", 0);
        if (msgId != null && msgSeq != null) {
            body.put("msg_id", msgId);
            body.put("msg_seq", msgSeq);
        }

        try {
            String url = "https://api.sgroup.qq.com/v2/groups/" + groupOpenid + "/messages";
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", "QQBot " + getAccessToken())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                    .build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            System.out.println("发送群消息 [" + resp.statusCode() + "] " + resp.body());
        } catch (Exception e) {
            System.out.println("发送群消息失败: " + e.getMessage());
        }
    }

    /**
     * 向用户发送私聊消息。
     */
    public void sendC2CMessage(String userOpenid, String content, String msgId, Long msgSeq) {
        ObjectNode body = mapper.createObjectNode();
        body.put("content", content);
        body.put("msg_type", 0);
        if (msgId != null && msgSeq != null) {
            body.put("msg_id", msgId);
            body.put("msg_seq", msgSeq);
        }

        try {
            String url = "https://api.sgroup.qq.com/v2/users/" + userOpenid + "/messages";
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", "QQBot " + getAccessToken())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                    .build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            System.out.println("发送私聊消息 [" + resp.statusCode() + "] " + resp.body());
        } catch (Exception e) {
            System.out.println("发送私聊消息失败: " + e.getMessage());
        }
    }


    // ==================== 对外：处理外部项目指令 ====================

    private void handleExternalCommand(WebSocket conn, String message) {
        try {
            JsonNode cmd = mapper.readTree(message);
            String type = cmd.path("type").asText("");

            if ("send_group_message".equals(type)) {
                String groupOpenid = cmd.path("group_openid").asText(null);
                String content = cmd.path("content").asText("");
                String msgId = cmd.has("msg_id") ? cmd.get("msg_id").asText() : null;
                Long msgSeq = (cmd.has("msg_seq") && cmd.get("msg_seq").isNumber())
                        ? cmd.get("msg_seq").asLong() : null;

                if (groupOpenid == null || content.isEmpty()) {
                    conn.send("{\"type\":\"error\",\"message\":\"group_openid 和 content 不能为空\"}");
                    return;
                }

                sendGroupMessage(groupOpenid, content, msgId, msgSeq);
                conn.send("{\"type\":\"ack\",\"message\":\"已提交发送\"}");
            } else if ("send_c2c_message".equals(type)) {
                String userOpenid = cmd.path("user_openid").asText(null);
                String content = cmd.path("content").asText("");
                String msgId = cmd.has("msg_id") ? cmd.get("msg_id").asText() : null;
                Long msgSeq = (cmd.has("msg_seq") && cmd.get("msg_seq").isNumber())
                        ? cmd.get("msg_seq").asLong() : null;

                if (userOpenid == null || content.isEmpty()) {
                    conn.send("{\"type\":\"error\",\"message\":\"user_openid 和 content 不能为空\"}");
                    return;
                }

                sendC2CMessage(userOpenid, content, msgId, msgSeq);
                conn.send("{\"type\":\"ack\",\"message\":\"已提交发送\"}");

            }else {
                conn.send("{\"type\":\"error\",\"message\":\"未知的 type\"}");
            }
        } catch (Exception e) {
            try {
                conn.send("{\"type\":\"error\",\"message\":\"" + e.getMessage() + "\"}");
            } catch (Exception ignored) {
            }
        }
    }

    // ==================== 对外 WebSocket 服务 ====================

    private static class ExternalApiServer extends WebSocketServer {
        private final QQBotProxy bot;

        public ExternalApiServer(int port, QQBotProxy bot) {
            super(new InetSocketAddress(port));
            this.bot = bot;
        }

        @Override
        public void onStart() {
            System.out.println("外部项目接入服务已启动，端口: " + getPort());
        }

        @Override
        public void onOpen(WebSocket conn, ClientHandshake handshake) {
            System.out.println("外部项目已连接: " + conn.getRemoteSocketAddress());
        }

        @Override
        public void onClose(WebSocket conn, int code, String reason, boolean remote) {
            System.out.println("外部项目已断开: " + conn.getRemoteSocketAddress());
        }

        @Override
        public void onMessage(WebSocket conn, String message) {
            bot.handleExternalCommand(conn, message);
        }

        @Override
        public void onError(WebSocket conn, Exception ex) {
            System.out.println("外部项目连接错误: " + ex.getMessage());
        }

        public void broadcast(String json) {
            for (WebSocket c : new ArrayList<>(getConnections())) {
                c.send(json);
            }
        }
    }

    // ==================== 启动 ====================

    public void run() {
        try {
            externalServer = new ExternalApiServer(externalPort, this);
            externalServer.start();
        } catch (Exception e) {
            System.out.println("外部 API 服务启动失败: " + e.getMessage());
            return;
        }

        while (true) {
            try {
                getAccessToken();
                connect();
            } catch (Exception e) {
                System.out.println("运行异常: " + e.getMessage());
            }

            System.out.println("QQ 连接已断开，5 秒后重连...");
            try {
                Thread.sleep(5000);
            } catch (InterruptedException ignored) {
            }
        }
    }

    private long parseLong(JsonNode node, long defaultValue) {
        if (node == null) return defaultValue;
        if (node.isNumber()) return node.asLong();
        try {
            return Long.parseLong(node.asText());
        } catch (Exception e) {
            return defaultValue;
        }
    }
}
