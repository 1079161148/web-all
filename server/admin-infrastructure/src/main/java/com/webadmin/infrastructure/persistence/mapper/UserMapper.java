package com.webadmin.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.webadmin.application.iam.query.UserPageQuery;
import com.webadmin.common.datascope.DataScope;
import com.webadmin.infrastructure.persistence.po.UserPO;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 用户 Mapper。
 *
 * <p>单表 CRUD 继承自 {@link BaseMapper}；<b>列表分页查询用 XML</b> ——
 * 它有联表（取部门名）、多个可选条件与动态排序，属于设计文档 §9.6
 * "复杂动态 SQL 用 XML Mapper"的情形。用 Wrapper 硬拼会退化成
 * 一长串 {@code .eq()} / {@code .like()} 且无法表达 JOIN。
 */
@Mapper
public interface UserMapper extends BaseMapper<UserPO> {

    /**
     * 用户分页查询（含部门名与角色聚合）。
     *
     * <h3>⚠️ {@link DataScope} 注解是本方法存在的主要理由</h3>
     * 数据权限的施加点是 <b>MyBatis 拦截器</b>，而拦截器只能通过
     * {@code mappedStatementId} 反查注解。因此"需要数据权限的查询"
     * <b>必须是 Mapper 上的一个具体方法</b>，不能是
     * {@code userMapper.selectPage(wrapper)} 这种调用 ——
     * 后者没有可标注的方法，等于绕过了数据权限。
     *
     * <p>这是最容易漏的一处：列表"看起来完全正常"，只是会多出
     * 不该看到的部门的数据，而没有任何报错或异常。
     *
     * <p>参数说明：
     * <ul>
     *   <li>{@code table = "iam_user"} —— 只对主表施加条件，
     *       不给联进来的 org_dept 也套上部门过滤（那会导致部门名显示异常）</li>
     *   <li>{@code deptAlias = "u.dept_id"} —— 带表别名限定。
     *       两张表都有 {@code dept_id} 概念，不加限定会报"列名不明确"</li>
     * </ul>
     */
    @DataScope(table = "iam_user", deptAlias = "u.dept_id", userAlias = "u.create_by")
    List<UserPO> selectUserPage(@Param("page") Page<UserPO> page,
                                @Param("query") UserPageQuery query);

    /**
     * 统计"当前（模拟）主体可见"的用户数 —— 数据权限模拟器用。
     *
     * <h3>为什么要复用同一份注解而不是另写条件</h3>
     * 数据过滤条件完全由 {@link DataScope} 注解决定（表名 + 两个列名），
     * 与 SQL 的其余部分无关。因此本方法只要与 {@code selectUserPage}
     * 使用<b>相同的注解</b>，拦截器施加的条件就与列表查询完全一致 ——
     * 预览结果因此等价于"打开列表看到的条数"。
     *
     * <p>若改为在应用层按范围自己拼 WHERE，就会有两套条件实现，
     * 而它们漂移的表现是"预览 8 条、实际 12 条"，且不报任何错。
     *
     * <p>{@code deptAlias} 不带表别名（本查询是单表），与 {@code selectTaskPage} 同理；
     * 而 {@code selectUserPage} 因有 JOIN 必须写 {@code u.dept_id}。
     */
    @DataScope(table = "iam_user", deptAlias = "dept_id", userAlias = "create_by")
    @Select("SELECT COUNT(*) FROM iam_user WHERE del_flag = 0")
    long countVisibleUsers();

    /**
     * 只读令牌版本号（鉴权热路径，见 {@code TokenVersionPort}）。
     *
     * <h3>为什么不用 {@code selectById}</h3>
     * 后者会带出全部列（含密码哈希、头像等），而这里只需要一个数字。
     * 鉴权是每请求都要走的路，查询宽度直接决定数据库压力。
     *
     * <h3>⚠️ 为什么要显式写 {@code del_flag = 0}</h3>
     * {@code @TableLogic} 只作用于 MyBatis-Plus 由 Wrapper / BaseMapper 生成的 SQL，
     * <b>原生 {@code @Select} 不会自动追加</b>。少了它，已删除用户仍会返回版本号 →
     * "用户已删除应拒绝其令牌"这一判定会失效（表现是"删掉的账号在其令牌过期前还能用"）。
     * 这类静默失效正是本查询单独写出来、而不是复用 selectById 的原因之一。
     *
     * @return 版本号；用户不存在或已删除时返回 {@code null}
     */
    @Select("SELECT token_version FROM iam_user WHERE id = #{id} AND del_flag = 0")
    Long selectTokenVersion(@Param("id") long id);

    /**
     * 批量提升某租户全部用户的令牌版本号（租户暂停/过期/关闭 → 全员强制下线）。
     *
     * <p>批量管理动作直接走 SQL（理由见 {@code TokenVersionPort#bumpAllForTenant}）。
     * 调用方必须已建立该租户的上下文：租户拦截器会追加 {@code tenant_id} 条件，
     * 与本句的显式条件重合但无害；反之若无上下文，拦截器行为未定义。
     */
    @Update("UPDATE iam_user SET token_version = token_version + 1 "
            + "WHERE tenant_id = #{tenantId} AND del_flag = 0")
    int bumpTokenVersionsForTenant(@Param("tenantId") long tenantId);
}
