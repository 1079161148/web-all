package com.webadmin.infrastructure.persistence;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.webadmin.application.platform.dto.ConfigDTO;
import com.webadmin.application.platform.port.ConfigPort;
import com.webadmin.application.platform.query.ConfigPageQuery;
import com.webadmin.common.api.PageResult;
import com.webadmin.common.tenant.TenantContext;
import com.webadmin.infrastructure.persistence.mapper.ConfigMapper;
import com.webadmin.infrastructure.persistence.po.ConfigPO;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

/** 参数配置端口实现。 */
@Repository
@RequiredArgsConstructor
public class ConfigPortImpl implements ConfigPort {

    private final ConfigMapper configMapper;

    @Override
    public PageResult<ConfigDTO> page(ConfigPageQuery query) {
        Page<ConfigPO> page = configMapper.selectPage(
                new Page<>(query.getPage(), query.getSize()),
                new LambdaQueryWrapper<ConfigPO>()
                        .in(ConfigPO::getTenantId, scope())
                        .like(query.getConfigName() != null && !query.getConfigName().isBlank(),
                                ConfigPO::getConfigName, query.getConfigName())
                        .like(query.getConfigKey() != null && !query.getConfigKey().isBlank(),
                                ConfigPO::getConfigKey, query.getConfigKey())
                        .orderByAsc(ConfigPO::getConfigKey));
        return PageResult.of(page.getRecords().stream().map(ConfigPortImpl::toDTO).toList(),
                page.getTotal(), (int) page.getCurrent(), (int) page.getSize());
    }

    @Override
    public Optional<ConfigDTO> findById(long id) {
        return Optional.ofNullable(configMapper.selectOne(new LambdaQueryWrapper<ConfigPO>()
                        .eq(ConfigPO::getId, id)
                        .in(ConfigPO::getTenantId, scope())))
                .map(ConfigPortImpl::toDTO);
    }

    @Override
    public Optional<String> findValueByKey(String configKey) {
        return Optional.ofNullable(findValuesByKeys(List.of(configKey)).get(configKey));
    }

    @Override
    public Map<String, String> findValuesByKeys(Collection<String> configKeys) {
        if (configKeys == null || configKeys.isEmpty()) {
            return Map.of();
        }
        List<ConfigPO> rows = configMapper.selectList(new LambdaQueryWrapper<ConfigPO>()
                .in(ConfigPO::getTenantId, scope())
                .in(ConfigPO::getConfigKey, configKeys)
                // 平台级(0) 在前、租户级在后 → 后者覆盖前者，实现"租户覆盖优先"
                .orderByAsc(ConfigPO::getTenantId));

        Map<String, String> merged = new LinkedHashMap<>();
        for (ConfigPO row : rows) {
            merged.put(row.getConfigKey(), row.getConfigValue());
        }
        return merged;
    }

    @Override
    public boolean keyExists(String configKey, Long excludeId) {
        return configMapper.exists(new LambdaQueryWrapper<ConfigPO>()
                .eq(ConfigPO::getTenantId, TenantContext.require())
                .eq(ConfigPO::getConfigKey, configKey)
                .ne(excludeId != null, ConfigPO::getId, excludeId));
    }

    @Override
    public Long insert(String configName, String configKey, String configValue,
                       Boolean builtin, String remark) {
        ConfigPO po = new ConfigPO();
        po.setId(IdWorker.getId());
        // 显式设置 tenant_id：本表不在拦截器覆盖范围，不设置会落成 0（=平台默认），
        // 即"租户管理员以为自己改了自己的配置，实际改了所有人的默认值"
        po.setTenantId(TenantContext.require());
        po.setConfigName(configName);
        po.setConfigKey(configKey);
        po.setConfigValue(configValue);
        // 业务侧创建的参数一律非内置；builtin 只由迁移脚本设定
        po.setBuiltin(builtin != null && builtin);
        po.setRemark(remark);
        configMapper.insert(po);
        return po.getId();
    }

    @Override
    public void update(long id, String configName, String configKey,
                       String configValue, String remark) {
        ConfigPO po = new ConfigPO();
        po.setId(id);
        po.setConfigName(configName);
        po.setConfigKey(configKey);
        po.setConfigValue(configValue);
        po.setRemark(remark);
        // 不更新 tenant_id / builtin：参数归属与内置标记不应被一次编辑改写
        configMapper.updateById(po);
    }

    @Override
    public void delete(long id) {
        configMapper.deleteById(id);
    }

    private List<Long> scope() {
        long tenantId = TenantContext.require();
        return tenantId == 0L ? List.of(0L) : List.of(0L, tenantId);
    }

    private static ConfigDTO toDTO(ConfigPO po) {
        return new ConfigDTO(po.getId(), po.getConfigName(), po.getConfigKey(),
                po.getConfigValue(), po.getBuiltin(), po.getRemark(),
                po.getTenantId(), po.getCreateTime());
    }
}
