package com.webadmin.infrastructure.persistence.po;

import com.baomidou.mybatisplus.annotation.TableName;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

/**
 * 用户-岗位关联 PO。
 *
 * <p>与其它关联表一致：<b>带 {@code tenant_id}</b>（虽然业务上不需要），
 * 目的是让它完全纳入租户拦截器的统一隔离，而不必加入例外名单。
 * 加一列的成本，远低于维护一份"哪些表不隔离"的清单。
 */
@Getter
@Setter
@TableName("iam_user_post")
public class UserPostPO {

    private Long tenantId;
    private Long userId;
    private Long postId;
    private Instant createTime;
}
