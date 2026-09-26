package com.webadmin.application.iam.port;

import com.webadmin.application.iam.dto.MessageView;
import com.webadmin.common.api.PageResult;
import java.util.List;

/**
 * 站内信数据访问端口。
 *
 * <h3>为什么"按接收者扇出"（一人一行）</h3>
 * 见 V1.0.12 迁移脚本的说明：未读角标是全系统最高频的消息查询，
 * "一人一行 + 布尔列"让它变成一次带索引的 COUNT，
 * 而"一条公告 + 已读关联表"则需要 GROUP/JOIN 推断。
 */
public interface MessagePort {

    /** 收件箱分页（按创建时间倒序）。 */
    PageResult<MessageView> page(long receiverId, int page, int size, Boolean isRead);

    /** 未读数（角标的数据源，高频）。 */
    long unreadCount(long receiverId);

    /**
     * 标记单条已读。
     *
     * @return false 表示该消息不存在或<b>不属于该接收者</b>。
     *         调用方对 false 静默成功即可：把别人的消息 ID 传进来
     *         不应得到"存在性"信息（与数据权限同一防泄露原则）
     */
    boolean markRead(long receiverId, long messageId);

    /** 全部标记已读。 */
    int markAllRead(long receiverId);

    /**
     * 扇出：为每个接收者创建一行。
     *
     * @return 按接收者顺序排列的已创建消息（供逐个推送实时通知）
     */
    List<MessageView> fanOut(long tenantId, List<Long> receiverIds, String msgType,
                             String title, String content, long senderId);
}
