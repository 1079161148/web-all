package com.webadmin.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.webadmin.infrastructure.persistence.po.AuditLogPO;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface AuditLogMapper extends BaseMapper<AuditLogPO> {

    @Select("SELECT * FROM sys_audit_log WHERE tenant_id = #{tenantId} " +
            "ORDER BY create_time DESC, id DESC LIMIT #{size} OFFSET #{offset}")
    List<AuditLogPO> selectPage(@Param("tenantId") long tenantId,
                                @Param("size") int size,
                                @Param("offset") int offset);

    @Select("SELECT COUNT(*) FROM sys_audit_log WHERE tenant_id = #{tenantId}")
    long countByTenant(@Param("tenantId") long tenantId);
}
