package com.webadmin.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.webadmin.infrastructure.persistence.po.UserPreferencePO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 用户偏好 Mapper。
 *
 * <p>upsert 用 {@code INSERT ... ON DUPLICATE KEY UPDATE} 而不是
 * "先查再插/改"：唯一键 (tenant_id, user_id, pref_key) 下的并发写
 * （同一用户开两个标签页同时改列布局）不需要应用层再加锁 ——
 * 数据库的唯一约束就是并发正确性。
 */
public interface UserPreferenceMapper extends BaseMapper<UserPreferencePO> {

    @Select("SELECT * FROM sys_user_preference "
            + "WHERE tenant_id = #{tenantId} AND user_id = #{userId} AND pref_key = #{key}")
    UserPreferencePO find(
            @Param("tenantId") long tenantId,
            @Param("userId") long userId,
            @Param("key") String key);

    @Insert("INSERT INTO sys_user_preference "
            + "(tenant_id, user_id, pref_key, pref_value, update_time) "
            + "VALUES (#{tenantId}, #{userId}, #{key}, #{value}, NOW(3)) "
            + "ON DUPLICATE KEY UPDATE pref_value = #{value}, update_time = NOW(3)")
    int upsert(
            @Param("tenantId") long tenantId,
            @Param("userId") long userId,
            @Param("key") String key,
            @Param("value") String value);
}
