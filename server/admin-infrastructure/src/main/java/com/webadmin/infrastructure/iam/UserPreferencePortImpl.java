package com.webadmin.infrastructure.iam;

import com.webadmin.application.iam.port.UserPreferencePort;
import com.webadmin.infrastructure.persistence.mapper.UserPreferenceMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 用户偏好端口实现（表：sys_user_preference）。 */
@Component
@RequiredArgsConstructor
public class UserPreferencePortImpl implements UserPreferencePort {

    private final UserPreferenceMapper mapper;

    @Override
    public String find(long tenantId, long userId, String key) {
        var po = mapper.find(tenantId, userId, key);
        return po == null ? null : po.getPrefValue();
    }

    @Override
    public void save(long tenantId, long userId, String key, String value) {
        mapper.upsert(tenantId, userId, key, value);
    }
}
