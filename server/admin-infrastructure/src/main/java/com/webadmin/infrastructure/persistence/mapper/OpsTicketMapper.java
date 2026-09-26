package com.webadmin.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.webadmin.infrastructure.persistence.po.OpsTicketPO;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 工单 Mapper。
 *
 * <h3>抢单的原子性在这里（条件 UPDATE）</h3>
 * "先查状态再更新"在并发下是错的（两个请求都读到 PENDING，都改成功）。
 * 条件 UPDATE 让数据库行锁天然串行化：只有一个请求的
 * {@code affected == 1}，其余全部拿到 0 —— 引擎层的"CAS"。
 * 超时升级同理：批量把过期的 PENDING 推进到 ESCALATED，
 * 条件里带 created_at 截止时间，多次执行幂等。
 */
public interface OpsTicketMapper extends BaseMapper<OpsTicketPO> {

    @Select("SELECT * FROM ops_ticket WHERE tenant_id = #{tenantId} " +
            "ORDER BY created_at DESC LIMIT 100")
    List<OpsTicketPO> selectRecent(@Param("tenantId") long tenantId);

    /**
     * 原子抢单。条件里带 state IN ('PENDING','ESCALATED')：
     * SLA 超时升级的工单同样允许被接手 —— 升级是"催办"，不是"作废"；
     * 若只允许 PENDING，升级即死锁（谁也无法再处理它）。
     */
    @Update("UPDATE ops_ticket SET state = 'CLAIMED', claimer = #{claimer}, update_time = NOW(3) " +
            "WHERE id = #{id} AND tenant_id = #{tenantId} AND state IN ('PENDING', 'ESCALATED')")
    int claim(@Param("id") long id, @Param("tenantId") long tenantId, @Param("claimer") String claimer);

    /**
     * 放回待处理池（看板"处理中 → 待处理"的拖拽）。
     * 仅接单人可放回；claimer 置空 —— 否则新接单人的覆盖依赖下一次 claim 的 UPDATE，
     * 而"放回后没人接"的中间态里该字段必须如实反映"没人负责"。
     */
    @Update("UPDATE ops_ticket SET state = 'PENDING', claimer = NULL, update_time = NOW(3) " +
            "WHERE id = #{id} AND tenant_id = #{tenantId} AND claimer = #{claimer} " +
            "AND state = 'CLAIMED'")
    int release(@Param("id") long id, @Param("tenantId") long tenantId, @Param("claimer") String claimer);

    @Update("UPDATE ops_ticket SET state = 'DONE', update_time = NOW(3) " +
            "WHERE id = #{id} AND tenant_id = #{tenantId} AND claimer = #{claimer} " +
            "AND state IN ('CLAIMED', 'ESCALATED')")
    int complete(@Param("id") long id, @Param("tenantId") long tenantId, @Param("claimer") String claimer);

    /** SLA 超时惰性升级：过期的 PENDING 批量推进为 ESCALATED（幂等）。 */
    @Update("UPDATE ops_ticket SET state = 'ESCALATED', update_time = NOW(3) " +
            "WHERE tenant_id = #{tenantId} AND state = 'PENDING' " +
            "AND DATE_ADD(created_at, INTERVAL sla_minutes MINUTE) < #{now}")
    int escalateOverdue(@Param("tenantId") long tenantId, @Param("now") LocalDateTime now);
}
