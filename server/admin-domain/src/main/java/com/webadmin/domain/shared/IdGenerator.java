package com.webadmin.domain.shared;

/**
 * 主键生成端口（依赖倒置）。
 *
 * <p>领域层需要"造一个新 ID"的能力，但<b>不能知道 ID 是怎么生成的</b> ——
 * 雪花算法涉及 workerId 分配、时钟回拨处理等基础设施细节。
 * 因此这里只声明"能给我一个唯一正数"，实现放在 infrastructure。
 *
 * <p>这样做的另一个收益：单元测试里可以注入 {@code () -> 1001L} 这样的确定性实现，
 * 断言"创建后 id 应该等于 1001"，而不是断言"id 大于 0"这种没有信息量的条件。
 *
 * <p>对比 {@code TenantIdGenerator}：那个是最早期为租户单独定义的端口，
 * 保留它以维持已有代码稳定；新增的聚合统一使用本接口。
 * 后续若做一次收敛，可以把两者合并为 {@code IdGenerator<T>} —— 但那是纯重构，
 * 不该和功能开发混在一起做（避免"顺手改"引入不可控风险）。
 */
@FunctionalInterface
public interface IdGenerator {

    /** 生成下一个全局唯一、趋势递增的正数 ID。 */
    long nextId();

    /** 便捷方法：直接生成 {@link UserId}。 */
    default UserId nextUserId() {
        return UserId.of(nextId());
    }

    /** 便捷方法：直接生成 {@link RoleId}。 */
    default RoleId nextRoleId() {
        return RoleId.of(nextId());
    }

    /** 便捷方法：直接生成 {@link MenuId}。 */
    default MenuId nextMenuId() {
        return MenuId.of(nextId());
    }

    /** 便捷方法：直接生成 {@link DeptId}。 */
    default DeptId nextDeptId() {
        return DeptId.of(nextId());
    }
}
