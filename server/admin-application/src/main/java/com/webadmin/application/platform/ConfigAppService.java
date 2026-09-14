package com.webadmin.application.platform;

import com.webadmin.application.platform.port.ConfigPort;
import com.webadmin.common.error.BizException;
import com.webadmin.domain.iam.IamErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 参数配置应用服务（L1 支撑域，事务脚本）。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConfigAppService {

    private final ConfigPort configPort;

    @Transactional
    public Long create(String configName, String configKey, String configValue, String remark) {
        if (configPort.keyExists(configKey, null)) {
            throw new BizException(IamErrorCode.CONFIG_KEY_DUPLICATED,
                    "参数键「" + configKey + "」已存在");
        }
        Long id = configPort.insert(configName, configKey, configValue, false, remark);
        log.info("创建参数 id={} key={}", id, configKey);
        return id;
    }

    @Transactional
    public void update(long id, String configName, String configKey,
                       String configValue, String remark) {
        configPort.findById(id).orElseThrow(() -> new BizException(
                IamErrorCode.CONFIG_NOT_FOUND, "参数不存在"));
        if (configPort.keyExists(configKey, id)) {
            throw new BizException(IamErrorCode.CONFIG_KEY_DUPLICATED,
                    "参数键「" + configKey + "」已存在");
        }
        configPort.update(id, configName, configKey, configValue, remark);
        // 参数值影响运行期行为，改完记一条 info 便于事后关联"某功能表现变了"
        log.info("修改参数 id={} key={} value={}", id, configKey, configValue);
    }

    @Transactional
    public void delete(long id) {
        var existing = configPort.findById(id).orElseThrow(() -> new BizException(
                IamErrorCode.CONFIG_NOT_FOUND, "参数不存在"));

        // 内置参数由迁移脚本维护，业务侧删掉它可能让某个功能失去取值来源。
        // 若要停用某个内置参数，应改它的值而不是删行 —— 删行后"配置项清单"
        // 会与代码里读取它的地方对不上，排查时很难想到是"少了一行"。
        if (Boolean.TRUE.equals(existing.builtin())) {
            throw new BizException(IamErrorCode.CONFIG_BUILTIN_IMMUTABLE,
                    "「" + existing.configName() + "」是系统内置参数，不允许删除");
        }
        configPort.delete(id);
        log.info("删除参数 id={} key={}", id, existing.configKey());
    }
}
