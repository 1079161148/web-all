package com.webadmin.common.fieldpermission;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 字段级权限：声明某个字段对哪些角色可见 / 隐藏 / 需脱敏。
 *
 * <h3>标注对象</h3>
 * 标在<b>响应 DTO</b> 的字段上（{@code UserResponse} 的 {@code phone} 等），
 * 不标在聚合或 PO 上。理由：
 * <ul>
 *   <li>权限是「对外暴露什么」的决策，属于表现层关注点；
 *       标在领域对象上会让领域模型耦合展示需求</li>
 *   <li>同一个手机号，在"用户详情"里可能脱敏、在"导出给财务"里可能完整 ——
 *       差异由 DTO 表达最自然</li>
 * </ul>
 *
 * <h3>三类策略的组合顺序</h3>
 * 判定顺序固定为：{@link #hiddenFor} 优先 → {@link #visibleFor} → {@link #maskStrategy}。
 * 也就是说：
 * <ol>
 *   <li>命中 {@code hiddenFor} → 直接返回 {@code null}（字段不出现在响应里）</li>
 *   <li>声明了 {@code visibleFor} 且当前用户角色不在其中 → 按 {@code maskStrategy} 脱敏</li>
 *   <li>否则 → 按 {@code maskStrategy} 脱敏（{@code NONE} 表示原值返回）</li>
 * </ol>
 * <b>顺序很重要</b>：若先判 {@code visibleFor}，那么同时写了
 * {@code hiddenFor} 和 {@code visibleFor} 的字段会因"可见"而绕过隐藏。
 * 把"隐藏"放在第一位是 fail-closed 的体现。
 *
 * <h3>⚠️ 导出与日志必须复用同一套策略</h3 * 最容易出的漏洞是"页面打码了、导出是明文"。
 * 本方案把脱敏做在 DTO 装配阶段（而不是 JSON 序列化阶段），
 * 因此<b>只要导出走同一批 DTO，就自动继承脱敏</b> —— 这是选择装配期脱敏的核心收益。
 * 若将来某个导出直接查库拼 Excel，必须显式调用同一个脱敏器。
 */
@Documented
@Target({ElementType.RECORD_COMPONENT, ElementType.FIELD, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface FieldPermission {

    /** 这些角色可以看到原值。为空表示不限制（但仍受 {@link #maskStrategy} 影响）。 */
    String[] visibleFor() default {};

    /** 这些角色完全看不到该字段（返回 null）。优先级高于 {@link #visibleFor}。 */
    String[] hiddenFor() default {};

    /** 无权限时的脱敏方式。{@link MaskStrategy#NONE} 表示不脱敏（仅控制显隐）。 */
    MaskStrategy maskStrategy() default MaskStrategy.SENSITIVE;

    /**
     * 字段为空时是否返回空串而不是 null。
     *
     * <p>默认 false（返回 null）。某些前端场景（表格直接渲染）希望得到 {@code "-"} 或空串，
     * 由前端统一处理更合适 —— 后端不该为了展示效果去改变数据的"空"语义。
     */
    boolean emptyAsBlank() default false;
}
