package com.webadmin.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.webadmin.infrastructure.persistence.po.MessagePO;
import org.apache.ibatis.annotations.Mapper;

/**
 * 站内信 Mapper。
 *
 * <p>全部查询走 {@code LambdaQueryWrapper}（单表、条件简单）：
 * 收件箱按 {@code receiver_id + is_read} 过滤即可，不需要 XML。
 * 租户条件由拦截器追加（plt_message 未在忽略名单中 → 自动隔离）。
 */
@Mapper
public interface MessageMapper extends BaseMapper<MessagePO> {
}
