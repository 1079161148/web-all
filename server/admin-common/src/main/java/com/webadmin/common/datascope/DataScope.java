package com.webadmin.common.datascope;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记在 Mapper 方法上，声明该查询需要施加<b>行级数据权限</b>过滤。
 *
 * <h3>为什么放在 Mapper 方法而不是 Service 或 Controller</h3>
 * 数据权限的施加点是 <b>SQL 改写</b>，而 SQL 由 Mapper 方法产生。
 * 标注在 Mapper 上意味着：
 * <ul>
 *   <li><b>无法绕过</b>：任何调用路径（AppService / 定时任务 / 其他 Mapper）最终都要经过它</li>
 *   <li>与 {@code TenantLineInnerInterceptor} 的施加层次一致 —— 两者都是"在 SQL 层加条件"，
 *       放在同一层才能保证它们的先后顺序是可推理的</li>
 * </ul>
 * 若标在 Service 上，就会出现"另一个 Service 也查了同一张表但忘了标"的漏洞。
 *
 * <h3>⚠️ 不标注 = 不加任何数据权限过滤</h3>
    * 这是<b>刻意的默认</b>，但方向是危险的，因此有两条补偿措施：
    * <ol>
    *   <li>租户隔离（{@code tenant_id}）是全局强制的，不需要标注 ——
    *       即使漏标数据权限，用户也绝不会看到别的租户的数据。这是最重要的一层兜底</li>
    *   <li>下表是<b>本项目的实际判定结果</b>（原注释写"对照设计文档 §7.3 的矩阵"，
    *       但 §7.3 只有条件对照表、没有模块矩阵，故在此落成清单，
    *       新增列表查询时按它判断，而不是凭感觉）</li>
    * </ol>
    *
    * <h3>数据权限适用范围清单（新增查询时照此判断）</h3>
    * <table border="1">
    *   <tr><th>查询</th><th>是否施加</th><th>理由</th></tr>
    *   <tr>
    *     <td>用户列表</td><td><b>是</b>（已标注）</td>
    *     <td>用户同时具备"所属部门"与"创建人"，两个维度都成立</td>
    *   </tr>
    *   <tr>
    *     <td>调研任务列表</td><td><b>是</b>（需先写入 {@code dept_id}）</td>
    *     <td>表里有 {@code dept_id}（负责部门），语义成立；但归属必须有写入方，
    *         否则过滤后"新建的任务连创建者自己都看不到"</td>
    *   </tr>
    *   <tr>
    *     <td>部门列表</td><td><b>否</b></td>
    *     <td>部门树必须<b>完整</b>：它同时是"数据范围配置界面"的数据源。
    *         一旦被过滤，用户在选择数据范围时只看得到自己部门，
    *         越配越窄、无法自愈 —— 这类"配置界面把自己锁死"的问题比越权更难发现</td>
    *   </tr>
    *   <tr>
    *     <td>岗位列表</td><td><b>否</b>（且不具备条件）</td>
    *     <td>{@code org_post} 没有 {@code dept_id} 列：岗位是租户级字典性质的数据</td>
    *   </tr>
    *   <tr>
    *     <td>菜单 / 字典 / 参数 / 租户</td><td><b>否</b></td>
    *     <td>平台或租户级的配置数据，没有"部门/个人"维度。租户列表尤其不能过滤：
    *         它属于平台治理，而数据权限是租户<b>内部</b>的行级规则</td>
    *   </tr>
    *   <tr>
    *     <td>同表的主键查询 / 详情</td><td><b>否</b></td>
    *     <td>能拿到主键说明调用链已经过列表或分配校验；此处再加条件会让
    *         "编辑自己有权看的记录"偶发失败，且失败原因难以复现</td>
    *   </tr>
    * </table>
 *
 * <p>为什么不默认全表施加并允许用 {@code @DataScopeIgnore} 关闭？
 * 因为绝大多数查询（按主键查、字典、配置、统计）都不该被部门范围过滤 ——
 * 默认全施加会让开发者在每个查询上都面对"要不要关掉"的决策，
 * 反而更容易出现"该关的没关、不该关的关了"。
 * <b>选择"显式开启"，并用扫描工具补偿遗漏风险。</b>
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface DataScope {

    /**
     * 部门列名（或带表别名的限定名）。
     *
     * <p>提供别名是因为数据权限几乎总是用在联表查询上，
     * 若不限定表名，在多表都有 {@code dept_id} 时 SQL 会报"列名不明确"。
     */
    String deptAlias() default "dept_id";

    /**
     * 创建人列名（{@code SELF} 范围使用）。
     */
    String userAlias() default "create_by";

    /**
     * 是否对超级管理员跳过过滤。
     *
     * <p>默认 {@code true}。虽然超管的 {@code dataScope} 通常就是 {@code ALL}，
     * 但显式提供这个开关的价值在于：<b>即使有人把超管角色的 dataScope 改成了 SELF，
     * 超管依然能看到全部数据</b> —— 避免因误改配置导致超管自己把系统锁死、
     * 只能改库恢复。
     *
     * <p>需要"让超管也受数据权限约束"的场景（比如给客户演示权限效果），
     * 显式设为 {@code false}。
     */
    boolean ignoreSuperAdmin() default true;

    /**
     * 需要在哪个表上施加条件。
     *
     * <p>留空表示"对查询中出现的所有表都尝试施加"。多表联查时建议显式指定主表名，
     * 否则可能给本不该过滤的关联表（如 {@code org_dept} 自身）也加上部门条件，
     * 造成"部门列表只显示自己"这类难以理解的现象。
     */
    String table() default "";
}
