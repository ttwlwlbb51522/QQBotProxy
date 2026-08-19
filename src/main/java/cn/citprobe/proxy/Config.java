package cn.citprobe;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.File;
import java.nio.file.Files;
import java.security.SecureRandom;
import java.util.HashSet;
import java.util.Set;

public class Config {

    private String appId;
    private String appSecret;
    private long intents;
    private int externalPort;
    private Set<String> adminIds;
    private String secret;   // 新增：共享密钥

    public static Config load(String path) {
        File file = new File(path);

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

            // ===== 读取共享密钥 =====
            String secretFileName = node.path("secret_file").asText("forwarding.secret");
            String configDir = file.getParent();
            File secretFile = new File(secretFileName);
            if (!secretFile.isAbsolute() && configDir != null) {
                secretFile = new File(configDir, secretFileName);
            }

            if (!secretFile.exists()) {
                String generated = generateSecret();
                Files.writeString(secretFile.toPath(), generated);
                System.out.println("已生成共享密钥文件: " + secretFile.getAbsolutePath());
            }

            c.secret = Files.readString(secretFile.toPath()).trim();
            if (c.secret.length() != 16) {
                System.out.println("警告: forwarding.secret 长度不是 16 位，当前长度 " + c.secret.length());
            }

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

    private static String generateSecret() {
        String chars = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
        SecureRandom random = new SecureRandom();
        StringBuilder sb = new StringBuilder(16);
        for (int i = 0; i < 16; i++) {
            sb.append(chars.charAt(random.nextInt(chars.length())));
        }
        return sb.toString();
    }

    private static void createDefaultConfig(File file) {
        try {
            ObjectMapper mapper = new ObjectMapper();

            ObjectNode root = mapper.createObjectNode();
            root.put("app_id", "你的AppID");
            root.put("app_secret", "你的AppSecret");
            root.put("intents", 33554432L);
            root.put("external_port", 18080);
            root.put("secret_file", "forwarding.secret");

            ArrayNode adminIds = root.putArray("admin_ids");
            adminIds.add("你的管理员openid或id");

            mapper.writerWithDefaultPrettyPrinter().writeValue(file, root);
            System.out.println("默认配置文件已生成，请填写后重新运行");
        } catch (Exception e) {
            System.out.println("生成默认配置文件失败: " + e.getMessage());
        }
    }

    // getter ...
    public String getAppId() { return appId; }
    public String getAppSecret() { return appSecret; }
    public long getIntents() { return intents; }
    public int getExternalPort() { return externalPort; }
    public Set<String> getAdminIds() { return adminIds; }
    public String getSecret() { return secret; }
}
