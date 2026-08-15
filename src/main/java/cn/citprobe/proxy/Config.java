package cn.citprobe.proxy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.File;
import java.util.HashSet;
import java.util.Set;

public class Config {

    private String appId;
    private String appSecret;
    private long intents;
    private int externalPort;
    private Set<String> adminIds;

    public static Config load(String path) {
        File file = new File(path);

        // 找不到配置文件时，自动生成默认配置
        if (!file.exists()) {
            System.out.println("未找到配置文件，正在生成默认配置: " + file.getAbsolutePath());
            createDefaultConfig(file);
            return null;
        }

        try {
            ObjectMapper mapper = new ObjectMapper();
            JsonNode node = mapper.readTree(file);

            Config c = new Config();
            c.appId = node.path("app_id").asText("");
            c.appSecret = node.path("app_secret").asText("");
            c.intents = node.path("intents").asLong(33554432L);
            c.externalPort = node.path("external_port").asInt(18080);

            Set<String> adminIds = new HashSet<>();
            for (JsonNode id : node.path("admin_ids")) {
                adminIds.add(id.asText());
            }
            c.adminIds = adminIds;

            if (c.appId.isEmpty() || "你的AppID".equals(c.appId)
                    || c.appSecret.isEmpty() || "你的AppSecret".equals(c.appSecret)) {
                System.out.println("请先填写 config.json 中的 app_id 和 app_secret");
                return null;
            }

            System.out.println("配置文件加载成功");
            return c;
        } catch (Exception e) {
            System.out.println("加载配置文件失败: " + e.getMessage());
            return null;
        }
    }

    private static void createDefaultConfig(File file) {
        try {
            ObjectMapper mapper = new ObjectMapper();

            ObjectNode root = mapper.createObjectNode();
            root.put("app_id", "你的AppID");
            root.put("app_secret", "你的AppSecret");
            root.put("intents", 33554432L);
            root.put("external_port", 18080);

            ArrayNode adminIds = root.putArray("admin_ids");
            adminIds.add("你的管理员openid或id");

            mapper.writerWithDefaultPrettyPrinter().writeValue(file, root);
            System.out.println("默认配置文件已生成，请填写后重新运行");
        } catch (Exception e) {
            System.out.println("生成默认配置文件失败: " + e.getMessage());
        }
    }

    // ==================== getter ====================

    public String getAppId() {
        return appId;
    }

    public String getAppSecret() {
        return appSecret;
    }

    public long getIntents() {
        return intents;
    }

    public int getExternalPort() {
        return externalPort;
    }

    public Set<String> getAdminIds() {
        return adminIds;
    }
}
