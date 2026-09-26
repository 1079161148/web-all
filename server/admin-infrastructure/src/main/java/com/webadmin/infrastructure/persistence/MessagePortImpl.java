package com.webadmin.infrastructure.persistence;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.webadmin.application.iam.dto.MessageView;
import com.webadmin.application.iam.port.MessagePort;
import com.webadmin.common.api.PageResult;
import com.webadmin.infrastructure.persistence.mapper.MessageMapper;
import com.webadmin.infrastructure.persistence.po.MessagePO;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 站内信数据访问实现。
 *
 * <p>查询全部是单表 + 简单条件，走 {@code LambdaQueryWrapper}；
 * 租户条件由拦截器追加（plt_message 未在忽略名单中 → 自动隔离）。
 */
@Component
@RequiredArgsConstructor
public class MessagePortImpl implements MessagePort {

    private final MessageMapper messageMapper;
    private final com.webadmin.domain.shared.IdGenerator idGenerator;

    @Override
    public PageResult<MessageView> page(long receiverId, int page, int size, Boolean isRead) {
        LambdaQueryWrapper<MessagePO> wrapper = new LambdaQueryWrapper<MessagePO>()
                .eq(MessagePO::getReceiverId, receiverId)
                .orderByDesc(MessagePO::getId);
        if (isRead != null) {
            wrapper.eq(MessagePO::getIsRead, isRead ? 1 : 0);
        }

        Page<MessagePO> result = messageMapper.selectPage(Page.of(page, size), wrapper);
        List<MessageView> records = new ArrayList<>();
        for (MessagePO po : result.getRecords()) {
            records.add(new MessageView(
                    po.getId(),
                    po.getTitle(),
                    po.getContent(),
                    po.getMsgType(),
                    po.getIsRead() != null && po.getIsRead() == 1,
                    po.getReadTime(),
                    po.getCreateTime()));
        }
        return new PageResult<>(records, result.getTotal(), page, size);
    }

    @Override
    public long unreadCount(long receiverId) {
        Long count = messageMapper.selectCount(new LambdaQueryWrapper<MessagePO>()
                .eq(MessagePO::getReceiverId, receiverId)
                .eq(MessagePO::getIsRead, 0));
        return count == null ? 0L : count;
    }

    @Override
    public boolean markRead(long receiverId, long messageId) {
        MessagePO po = messageMapper.selectById(messageId);
        if (po == null || po.getReceiverId() == null || po.getReceiverId() != receiverId) {
            // 不存在，或不属于该接收者 —— 两者对外表现一致（防存在性泄露）
            return false;
        }
        if (po.getIsRead() != null && po.getIsRead() == 1) {
            return true; // 幂等：已读再标一次仍是成功
        }
        po.setIsRead(1);
        po.setReadTime(java.time.Instant.now());
        return messageMapper.updateById(po) > 0;
    }

    @Override
    public int markAllRead(long receiverId) {
        List<MessagePO> unread = messageMapper.selectList(new LambdaQueryWrapper<MessagePO>()
                .eq(MessagePO::getReceiverId, receiverId)
                .eq(MessagePO::getIsRead, 0));
        int updated = 0;
        for (MessagePO po : unread) {
            po.setIsRead(1);
            po.setReadTime(java.time.Instant.now());
            if (messageMapper.updateById(po) > 0) {
                updated++;
            }
        }
        return updated;
    }

    @Override
    public List<MessageView> fanOut(long tenantId, List<Long> receiverIds, String msgType,
                                    String title, String content, long senderId) {
        List<MessageView> created = new ArrayList<>(receiverIds.size());
        java.time.Instant now = java.time.Instant.now();
        for (Long receiverId : receiverIds) {
            MessagePO po = new MessagePO();
            // 主键策略是 INPUT（全表统一雪花 ID，见 TenantPO 的说明），
            // 因此插入前必须显式生成 —— 漏了就是 "Column 'id' cannot be null"
            po.setId(idGenerator.nextId());
            po.setTenantId(tenantId);
            po.setReceiverId(receiverId);
            po.setMsgType(msgType);
            po.setTitle(title);
            po.setContent(content);
            po.setSenderId(senderId);
            po.setIsRead(0);
            po.setCreateTime(now);
            messageMapper.insert(po);
            created.add(new MessageView(po.getId(), title, content, msgType, false, null, now));
        }
        return created;
    }
}
