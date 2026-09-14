package com.webadmin.infrastructure.config;

import com.baomidou.mybatisplus.extension.plugins.handler.TenantLineHandler;
import com.webadmin.common.tenant.TenantContext;
import java.util.Locale;
import java.util.Set;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.LongValue;

/**
 * 多租户 SQL 拦截处理器。
 *
 * <p>作用：对所有业务表的 SQL 自动追加 {@code tenant_id = ?} 条件，
 * 让业务代码<b>零侵入</b>地获得行级租户隔离（设计文档 §6.3）。
 *
 * <h3>两处必须显式声明的设计</h3>
 *
 * <p><b>1. 忽略名单是白名单式的显式清单</b>：只有在这里登记过的表才跳过租户条件，
 * 其余一律加条件。<b>禁止「默认放行」</b> —— 那意味着新增业务表时一旦忘记登记，
 * 就会静默地跨租户读数据，而这不会报错，只会在某天被客户发现。
 *
 * <p><b>2. 租户上下文缺失时直接抛异常</b>：调用 {@link TenantContext#require()}
 * 而不是给一个默认值。宁可快速失败（500 + 明确提示），也不要静默地返回错误租户的数据。
 */
public class TenantLineHandlerImpl implements TenantLineHandler {

    /** 所有业务表的租户列名。 */
    private static final String TENANT_COLUMN = "tenant_id";

    /**
     * 不做租户隔离的表（平台级 / 框架级）。
     *
     * <p>新增条目时必须说明理由，并在 PR 中由平台 owner 评审 ——
     * 往这里加表等于放宽隔离，是安全敏感变更。
     *
     * <p><b>本名单的规模应当被刻意控制在最小。</b>凡是能通过「加一个 tenant_id 列」
     * 纳入统一隔离的表，就不要放进这里 —— 例外越多，越容易在某个不起眼的表上漏掉隔离。
     * 关联表（iam_user_role / iam_role_menu / iam_role_dept / iam_user_post）就是
     * 按这个原则处理的：它们本来不需要 tenant_id，但仍加上了该列，从而完全无需例外。
     */
    private static final Set<String> IGNORED_TABLES = Set.of(
            // 租户表本身是平台级数据（列表、配额、生命周期都跨租户）
            "iam_tenant",

            // 菜单是平台级共享定义：所有租户共用同一套菜单树，
            // 租户间可见范围差异由「角色-菜单分配 × 租户套餐」表达，而不是各存一份菜单树。
            // 若这里不放行，tenant_id=0 的菜种子数据会被 `tenant_id = ?` 过滤掉，导致所有菜单凭空消失。
            "iam_menu",

            // 字典与配置需要「平台默认值(tenant_id=0) + 租户覆盖值」两级 fallback。
            // 拦截器只会拼 `tenant_id = ?`，那会把平台默认行一并过滤掉 ——
            // 因此这里必须放行，改由查询层显式写 `tenant_id IN (0, ?)` 来正确表达 fallback 语义。
            // ⚠️ 这意味着**责任从拦截器转移到了查询层**：任何人写这两个表的查询都必须带
            //    `tenant_id IN (0, ?)` 条件，不能只写 `tenant_id = ?`。这是本名单里最需要小心的一条。
            "plt_dict_type",
            "plt_dict_data",
            "plt_config",

            // 框架表：Spring Modulith 的事务性 Outbox
            "event_publication",
            // 迁移历史
            "flyway_schema_history"
    );

    @Override
    public Expression getTenantId() {
        // 上下文缺失即抛异常（防漏隔离），不做默认值兜底
        return new LongValue(TenantContext.require());
    }

    @Override
    public String getTenantIdColumn() {
        return TENANT_COLUMN;
    }

    @Override
    public boolean ignoreTable(String tableName) {
        return tableName != null && IGNORED_TABLES.contains(tableName.toLowerCase(Locale.ROOT));
    }
}
