package com.webadmin.infrastructure.persistence.po;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.webadmin.domain.iam.model.tenant.TenantStatus;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

/**
 * 租户持久化对象（PO）。
 *
 * <h3>为什么不直接用聚合根做实体</h3>
 * 这是设计文档 §4.8 强调的核心难点：MyBatis-Plus 的实体是<b>贫血的、带框架注解的</b>，
 * 而 {@code Tenant} 聚合根是<b>充血的、零框架注解的</b>。二者强行合并会导致：
 * <ul>
 *   <li>领域层被 {@code @TableName} / {@code @Version} 污染（违反 ArchUnit 红线）</li>
 *   <li>为了满足 MyBatis 的「无参构造 + setter」要求，聚合根被迫暴露 setter，
 *       不变量形同虚设</li>
 * </ul>
 * 因此二者分离，由 {@code TenantConverter} 负责转换。
 *
 * <p>PO 的字段顺序刻意与 {@code V1.0.0__init_database.sql} 中的列顺序保持一致，便于对照。
 */
@Getter
@Setter
@TableName("iam_tenant")
public class TenantPO {

    /** 主键：雪花算法生成，不使用数据库自增（多租户 + 分库演进需要全局唯一有序 ID）。 */
    @TableId(type = IdType.INPUT)
    private Long id;

    /** 租户编码。业务上创建后不可修改，由聚合根保证。 */
    private String code;

    private String name;

    /**
     * 状态。
     *
     * <p>直接使用领域枚举：MyBatis 默认的 {@code EnumTypeHandler} 按 {@code name()} 存取，
     * 与数据库中的字符串列天然对应，无需额外映射。
     */
    private TenantStatus status;

    private String planCode;

    private String planName;

    /** 剩余配额（语义为「剩余可用量」，与领域层 {@code Quota} 一致）。 */
    private Long remainingUsers;

    private Long remainingStorageBytes;

    private Long remainingApiCalls;

    private Instant expireTime;

    private String remark;

    // ------------------------------------------------------------------
    // 审计字段
    // ------------------------------------------------------------------

    private Long createBy;

    /** 由 {@code auditMetaObjectHandler} 自动填充。 */
    @TableField(fill = FieldFill.INSERT)
    private Instant createTime;

    private Long updateBy;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Instant updateTime;

    /**
     * 逻辑删除标记。
     *
     * <p>语义：{@code 0} 表示未删除；删除时写入该行自己的主键值
     * （全局配置 {@code logic-delete-value: id}，原因见 application.yml 与 V1.0.1 的说明）。
     * 因此类型必须是 {@code Long} 而非 {@code Integer} —— 雪花 ID 有 19 位，INT 会溢出。
     *
     * <p>注意：{@code iam_tenant} 表实际<b>不会</b>发生逻辑删除 —— 租户的终点是
     * {@code status = CLOSED}（终态），不做物理删除，以保证历史数据的租户归属不悬空。
     * 本字段保留是为了满足全表通用字段规范（设计文档 §9.2），
     * 也使租户表可以复用同一套 BaseMapper 行为。
     */
    @TableLogic
    private Long delFlag;

    /** 乐观锁版本。更新时 MyBatis-Plus 会追加 {@code AND version = ?} 并自动 +1。 */
    @Version
    private Integer version;
}
