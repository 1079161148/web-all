package com.webadmin.application.platform.port;

import com.webadmin.application.platform.dto.ConfigDTO;
import com.webadmin.application.platform.query.ConfigPageQuery;
import com.webadmin.common.api.PageResult;
import java.util.Map;
import java.util.Optional;

/**
 * 参数配置读写端口。
 *
 * <p>⚠️ 实现必须自己带 {@code tenant_id IN (0, ?)}（本表在租户拦截器忽略名单里），
 * 理由同 {@code DictQueryPort}。
 */
public interface ConfigPort {

    PageResult<ConfigDTO> page(ConfigPageQuery query);

    Optional<ConfigDTO> findById(long id);

    /**
     * 按参数键取有效值（租户覆盖优先，否则平台默认）。
     *
     * <p>这是"参数配置"真正的使用入口：业务代码要的是"某个键当前的值"，
     * 而不是"某一行记录"。单独提供它，可以让调用方不必关心两级 fallback 的细节。
     */
    Optional<String> findValueByKey(String configKey);

    /**
     * 批量取参数值，一次查询返回全部命中项。
     *
     * <p>为什么需要它：页面初始化常常要读 3~5 个参数。
     * 逐个 {@code findValueByKey} 会产生 3~5 次查询，而它们在同一次渲染里发生 ——
     * 正是设计文档 §12.7「接口瀑布」要治理的形态。
     */
    Map<String, String> findValuesByKeys(java.util.Collection<String> configKeys);

    boolean keyExists(String configKey, Long excludeId);

    Long insert(String configName, String configKey, String configValue,
                Boolean builtin, String remark);

    void update(long id, String configName, String configKey, String configValue, String remark);

    void delete(long id);
}
