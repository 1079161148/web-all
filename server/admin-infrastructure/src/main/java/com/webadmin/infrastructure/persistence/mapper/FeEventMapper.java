package com.webadmin.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.webadmin.infrastructure.persistence.po.FeEventPO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/**
 * 前端可观测事件 Mapper。
 *
 * <p>聚合（慢接口/Top 页面）直接用 GROUP BY 在库上做：
 * 事件表是追加型的，把全量拉到应用内存再聚合是给自己找罪受。
 * 时间窗（1 小时 / 1 天）写死在 SQL —— 它们是"查看口径"，
 * 变更时改这里即可，客户端无需跟着发版。
 */
public interface FeEventMapper extends BaseMapper<FeEventPO> {

    @Insert("<script>"
            + "INSERT INTO fe_event (tenant_id, user_id, type, name, page, duration_ms, detail, created_at) VALUES "
            + "<foreach collection='events' item='e' separator=','>"
            + "(#{tenantId}, #{userId}, #{e.type}, #{e.name}, #{e.page}, #{e.durationMs}, #{e.detail}, NOW(3))"
            + "</foreach>"
            + "</script>")
    int insertBatch(
            @Param("tenantId") long tenantId,
            @Param("userId") Long userId,
            @Param("events") List<FeEventPO> events);

    @Select("SELECT name AS name, COUNT(*) AS calls, AVG(duration_ms) AS avgMs, "
            + "MAX(duration_ms) AS maxMs FROM fe_event "
            + "WHERE tenant_id = #{tenantId} AND type = 'api' AND duration_ms IS NOT NULL "
            + "AND created_at >= DATE_SUB(NOW(3), INTERVAL 1 HOUR) "
            + "GROUP BY name ORDER BY avgMs DESC LIMIT 10")
    List<Map<String, Object>> selectSlowApis(@Param("tenantId") long tenantId);

    @Select("SELECT name, page, detail, created_at AS createdAt FROM fe_event "
            + "WHERE tenant_id = #{tenantId} AND type = 'error' "
            + "ORDER BY id DESC LIMIT 50")
    List<Map<String, Object>> selectRecentErrors(@Param("tenantId") long tenantId);

    @Select("SELECT page AS name, COUNT(*) AS views FROM fe_event "
            + "WHERE tenant_id = #{tenantId} AND type = 'route' AND page IS NOT NULL "
            + "AND created_at >= DATE_SUB(NOW(3), INTERVAL 1 DAY) "
            + "GROUP BY page ORDER BY views DESC LIMIT 10")
    List<Map<String, Object>> selectTopPages(@Param("tenantId") long tenantId);
}
