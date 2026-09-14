package com.webadmin.infrastructure.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.DataPermissionInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import com.webadmin.common.api.PageQuery;
import com.webadmin.infrastructure.datascope.DataScopePermissionHandler;
import java.time.Instant;
import org.apache.ibatis.reflection.MetaObject;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Plus 配置。
 *
 * <h3>⚠️ 拦截器顺序是本类最关键的部分（设计文档 §7.3）</h3>
 *
 * <pre>
 *   1. TenantLineInnerInterceptor       租户隔离   —— 必须最外层
 *   2. DataPermissionInterceptor        数据权限   —— P1 接入
 *   3. PaginationInnerInterceptor       分页       —— 必须最后
 *   4. OptimisticLockerInnerInterceptor 乐观锁
 * </pre>
 *
 * <p><b>为什么分页必须放在最后</b>：分页拦截器会额外生成一条 {@code count} 语句。
 * 只有排在它<b>之前</b>的拦截器（租户、数据权限）才会同时作用于 {@code count} 与
 * {@code select}。反过来，如果分页在前面，{@code count} 就会缺少租户/数据权限条件 ——
 * 结果是「列表条数对不上总数、分页页数错乱」，而且<b>不报错</b>，极难排查。
 *
 * <p>必须有集成测试断言 {@code count} 与 {@code list} 使用相同过滤条件
 * （见设计文档 §13.3 必测清单第 3 项）。
 */
@Configuration
@MapperScan("com.webadmin.infrastructure.persistence.mapper")
public class MybatisPlusConfig {

    /** 单页最大条数，防止前端传入超大 size 拖垮数据库。与 {@link PageQuery#MAX_SIZE} 保持一致。 */
    private static final long MAX_PAGE_SIZE = PageQuery.MAX_SIZE;

    @Bean
    public TenantLineHandlerImpl tenantLineHandler() {
        return new TenantLineHandlerImpl();
    }

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor(
            TenantLineHandlerImpl tenantLineHandler,
            DataScopePermissionHandler dataScopePermissionHandler) {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();

        // ① 租户隔离：最外层，保证后续所有条件都建立在租户边界之内
        interceptor.addInnerInterceptor(new TenantLineInnerInterceptor(tenantLineHandler));

        // ② 数据权限（@DataScope + 部门树行级过滤）
        //    ⚠️ 位置必须在分页之前，否则 MyBatis-Plus 生成的 count 语句
        //    会漏掉部门范围条件 —— 表现为"列表有 3 条、总数却是 30"，
        //    且分页页数错乱。这类问题不报错，只能靠比对 count 与 list 发现。
        interceptor.addInnerInterceptor(new DataPermissionInterceptor(dataScopePermissionHandler));

        // ③ 分页：必须最后，保证 count 与 select 携带完全一致的过滤条件
        PaginationInnerInterceptor pagination = new PaginationInnerInterceptor(DbType.MYSQL);
        pagination.setMaxLimit(MAX_PAGE_SIZE);
        // 溢出总页数后不返回空列表，而是回到首页，避免前端出现「第 99 页空白」
        pagination.setOverflow(false);
        interceptor.addInnerInterceptor(pagination);

        // ④ 乐观锁：@Version 字段不匹配时更新影响行数为 0，仓储据此抛并发异常
        interceptor.addInnerInterceptor(new OptimisticLockerInnerInterceptor());

        return interceptor;
    }

    /**
     * 审计字段自动填充。
     *
     * <p>只填充时间字段；{@code create_by} / {@code update_by} 需要用户上下文，
     * 待 P1 接入认证后在 {@code UserContext} 中提供。
     */
    @Bean
    public MetaObjectHandler auditMetaObjectHandler() {
        return new MetaObjectHandler() {

            @Override
            public void insertFill(MetaObject metaObject) {
                Instant now = Instant.now();
                strictInsertFill(metaObject, "createTime", Instant.class, now);
                strictInsertFill(metaObject, "updateTime", Instant.class, now);
            }

            @Override
            public void updateFill(MetaObject metaObject) {
                strictUpdateFill(metaObject, "updateTime", Instant.class, Instant.now());
            }
        };
    }
}
