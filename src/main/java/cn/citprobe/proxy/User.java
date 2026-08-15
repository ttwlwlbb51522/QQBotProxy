package cn.citprobe.proxy;

import com.fasterxml.jackson.annotation.JsonProperty;

public class User {

    @JsonProperty("group_nickname")
    private String groupNickname;

    @JsonProperty("openid")
    private String openid;

    @JsonProperty("is_group_owner")
    private boolean groupOwner;

    @JsonProperty("is_group_admin")
    private boolean groupAdmin;

    @JsonProperty("is_bot_admin")
    private boolean botAdmin;

    @JsonProperty("message")
    private String message;

    @JsonProperty("group_openid")
    private String groupOpenid;

    @JsonProperty("msg_id")
    private String msgId;

    @JsonProperty("msg_seq")
    private Long msgSeq;

    @JsonProperty("timestamp")
    private long timestamp;

    // ==================== getter / setter ====================

    public String getGroupNickname() {
        return groupNickname;
    }

    public void setGroupNickname(String groupNickname) {
        this.groupNickname = groupNickname;
    }

    public String getOpenid() {
        return openid;
    }

    public void setOpenid(String openid) {
        this.openid = openid;
    }

    public boolean isGroupOwner() {
        return groupOwner;
    }

    public void setGroupOwner(boolean groupOwner) {
        this.groupOwner = groupOwner;
    }

    public boolean isGroupAdmin() {
        return groupAdmin;
    }

    public void setGroupAdmin(boolean groupAdmin) {
        this.groupAdmin = groupAdmin;
    }

    public boolean isBotAdmin() {
        return botAdmin;
    }

    public void setBotAdmin(boolean botAdmin) {
        this.botAdmin = botAdmin;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public String getGroupOpenid() {
        return groupOpenid;
    }

    public void setGroupOpenid(String groupOpenid) {
        this.groupOpenid = groupOpenid;
    }

    public String getMsgId() {
        return msgId;
    }

    public void setMsgId(String msgId) {
        this.msgId = msgId;
    }

    public Long getMsgSeq() {
        return msgSeq;
    }

    public void setMsgSeq(Long msgSeq) {
        this.msgSeq = msgSeq;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }
}
