package com.webadmin.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.webadmin.application.iam.query.UserPageQuery;
import com.webadmin.common.datascope.DataScope;
import com.webadmin.infrastructure.persistence.po.UserPO;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

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
}
