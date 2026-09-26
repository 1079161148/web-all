package com.webadmin.application.iam.port;

/**
 * 用户偏好端口（用户级配置的持久化）。
 *
 * <h3>为什么偏好值得一个独立端口而不是塞进用户表</h3>
 * 偏好是<b>高频写、弱一致、无业务约束</b>的数据：拖一下列宽就写一次，
 * 写丢了最多恢复默认布局。它与用户表那种"改一次、强校验"的主数据
 * 写入特征完全相反，混在一起只会让用户表的更新语义变浑浊。
 *
 * <h3>边界（同样重要）</h3>
 * 键与值在这里<b>只是不透明字节</b>：本层不理解 "table:user-list" 的含义，
 * 也不校验 JSON 的内部结构 —— 结构是前端各消费方自己的契约。
 * 服务端只把守两条底线：键格式可路由、值不超限（防把偏好存储当免费网盘）。
 */
public interface UserPreferencePort {

    /**
     * 读取偏好。不存在返回 {@code null} —— 由上层决定"没有偏好"的语义
     * （前端用它区分"从未设置"与"显式设为空"，两者都走默认布局）。
     */
    String find(long tenantId, long userId, String key);

    /** 写入偏好（upsert）。值上限 8KB、键格式由端口实现统一校验。 */
    void save(long tenantId, long userId, String key, String value);
}
