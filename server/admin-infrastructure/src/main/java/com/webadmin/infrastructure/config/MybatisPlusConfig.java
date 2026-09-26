package com.webadmin.infrastructure.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.DataPermissionInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import com.webadmin.application.security.CurrentUser;
import com.webadmin.application.security.CurrentUserPort;
import com.webadmin.common.api.PageQuery;
import com.webadmin.infrastructure.datascope.DataScopePermissionHandler;
import java.time.Instant;
import org.apache.ibatis.reflection.MetaObject;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

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
     * <h3>为什么 {@code create_by} 不是一个"可有可无的元数据"</h3>
     * 四维权限里的<b>行级数据权限</b>，其 {@code SELF} 范围正是用
     * {@code create_by = 当前用户} 表达的（见 {@code DataScopeConditionBuilder}）。
     * 这一列若长期为空，现象是「数据范围选了『仅本人』的角色<b>看不到任何数据</b>」——
     * 而根因藏在一个与权限毫无关系的填充器里，排查方向极易跑偏。
     *
     * <p>因此"填审计字段"是数据权限能否成立的前置条件，而不是收尾工作。
     *
     * <h3>⚠️ 为什么"只改填充器、不加 PO 注解"是行不通的（实测踩过）</h3>
     * 一开始的写法是想省掉二十多个 PO 的注解：用非严格的
     * {@code setFieldValByName} 按字段名塞值。实测<b>完全无效</b> ——
     * 打出来的 INSERT 是：
     * <pre>
     *   INSERT INTO iam_user (id, tenant_id, username, ..., create_time, update_time)
     *   -- 注意：没有 create_by / update_by
     * </pre>
     * 根因是 MyBatis-Plus 的两个事实叠加：
     * <ol>
     *   <li>只有当字段标了 {@code @TableField(fill = ...)} 时，该列才会<b>无条件</b>
     *       出现在 INSERT 列清单里；没标注解的字段生成的是
     *       {@code <if test="et.createBy != null">create_by,</if>}</li>
     *   <li>这段动态 SQL 在 {@code BoundSql} 构建时就求值，而填充发生在
     *       之后的 {@code ParameterHandler.setParameters} 阶段 ——
     *       <b>填充晚于列清单的求值</b></li>
     * </ol>
     * 于是"填充器里塞了值"与"值进入 SQL"是两件事：后者由 PO 注解决定。
     * 结论：PO 上的 {@code fill} 注解<b>不是可选的样板</b>，它是让字段进入
     * 写语句开关；填充器只负责"值取什么"。
     *
     * <h3>仍然集中在这一个填充器里取值</h3>
     * 注解只声明"这列要自动填"，取值的规则（取当前用户、无上下文留空）
     * 仍然只有这一处，新增 PO 只需照抄两行注解，不必各自实现取用户的逻辑。
     *
     * <h3>没有登录上下文时留空，而不是填 0</h3>
     * 启动期初始化（{@code AdminUserInitializer}）、定时任务、测试都没有当前用户。
     * 填 0 会让"系统创建的数据"看起来像某个用户 ID，将来做数据归属统计会误导；
     * 留 {@code NULL} 才是"无创建人"的准确表达。
     *
     * <p>{@code CurrentUserPort} 走 {@code @Lazy}：它与 MyBatis 拦截器链上的
     * {@code DataScopePermissionHandler} 同源，凡是"能从 MyBatis 到达的 Bean"
     * 都应默认按"查询期才可用"设计（原因见 {@code DeptHierarchyLookup} 的注释）。
     */
    @Bean
    public MetaObjectHandler auditMetaObjectHandler(@Lazy CurrentUserPort currentUserPort) {
        return new MetaObjectHandler() {

            @Override
            public void insertFill(MetaObject metaObject) {
                Instant now = Instant.now();
                strictInsertFill(metaObject, "createTime", Instant.class, now);
                strictInsertFill(metaObject, "updateTime", Instant.class, now);

                Long userId = currentUserId();
                if (userId != null) {
                    setFieldValByName("createBy", userId, metaObject);
                    setFieldValByName("updateBy", userId, metaObject);
                }
            }

            @Override
            public void updateFill(MetaObject metaObject) {
                strictUpdateFill(metaObject, "updateTime", Instant.class, Instant.now());

                Long userId = currentUserId();
                if (userId != null) {
                    setFieldValByName("updateBy", userId, metaObject);
                }
            }

            /** 当前用户 ID；无登录上下文（启动初始化、定时任务）返回 {@code null}。 */
            private Long currentUserId() {
                return currentUserPort.currentUser().map(CurrentUser::userId).orElse(null);
            }
        };
    }
}
