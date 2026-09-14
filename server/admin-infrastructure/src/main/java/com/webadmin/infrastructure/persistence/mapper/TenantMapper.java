package com.webadmin.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.webadmin.application.iam.query.TenantPageQuery;
import com.webadmin.infrastructure.persistence.po.TenantPO;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 租户 Mapper。
 *
 * <p>继承 {@link BaseMapper} 获得单表 CRUD；复杂动态查询（分页列表）用
 * {@code TenantMapper.xml} 手写 SQL —— 这是设计文档 §2.1 选型 MyBatis-Plus 的理由：
 * 「中后台 90% 是列表查询，对复杂动态 SQL 的控制力最强」。
 *
 * <p>⚠️ 本接口的 SQL 会被 {@code TenantLineInnerInterceptor} 自动追加租户条件，
 * 但 {@code iam_tenant} 在忽略名单中（租户列表本身是平台级功能），
 * 因此分页查询需要自行处理租户维度的过滤，见 XML 中的说明。
 */
@Mapper
public interface TenantMapper extends BaseMapper<TenantPO> {

    /**
     * 分页查询租户列表。
     *
     * <p>分页本身由 {@code PaginationInnerInterceptor} 改写 SQL 完成，
     * 因此这里<b>只写查询条件与排序，不写 LIMIT</b>。
     */
    List<TenantPO> selectPageByQuery(@Param("q") TenantPageQuery query);

    /**
     * 统计总数。
     *
     * <p>理论上分页拦截器会自动生成 count 语句，这里保留显式方法是为了
     * 让「count 与 list 条件必须一致」这一点在代码里可见 ——
     * 两者共用同一段 {@code <sql id="pageConditions">} 片段，从结构上杜绝漏条件。
     */
    long countByQuery(@Param("q") TenantPageQuery query);

    /** 按编码查询（跨租户，用于创建时的唯一性校验）。 */
    TenantPO selectByCode(@Param("code") String code);
}
