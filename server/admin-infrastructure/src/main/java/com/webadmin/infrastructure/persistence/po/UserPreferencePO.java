package com.webadmin.infrastructure.persistence.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/** 用户偏好 PO（用户级 KV；键格式与值上限由应用层统一把关）。 */
@Getter
@Setter
@TableName("`sys_user_preference`")
public class UserPreferencePO {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long tenantId;

    private Long userId;

    private String prefKey;

    /** 不透明字符串：本层不解释其内部结构（契约归前端消费方）。 */
    private String prefValue;

    private LocalDateTime updateTime;
}
