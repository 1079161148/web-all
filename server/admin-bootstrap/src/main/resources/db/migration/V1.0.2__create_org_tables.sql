-- =====================================================================
-- V1.0.2 组织表：部门（物化路径）/ 岗位
--
-- 部门采用 ancestors 物化路径而非递归查询：
--   · DEPT_AND_CHILD 数据范围只需 `ancestors LIKE '0,1,%'` 前缀匹配，一条 SQL 搞定
--   · 代价是移动部门时要级联更新子树的 ancestors（用一条 UPDATE 批量完成）
--   · 对比递归 CTE：MySQL 8 支持递归 CTE，但它无法走索引，且拼接进
--     MyBatis 拦截器的动态条件会很别扭；物化路径是"用写复杂度换读性能"，
--     而部门树的读远多于写
--
-- del_flag 语义同 V1.0.1（存 0 或本行 id），见该文件头部说明。
-- =====================================================================

CREATE TABLE IF NOT EXISTS `org_dept`
(
    `id`             BIGINT       NOT NULL COMMENT '主键（雪花ID）',
    `tenant_id`      BIGINT       NOT NULL DEFAULT 0 COMMENT '租户ID，0=平台级',
    `parent_id`      BIGINT       NOT NULL DEFAULT 0 COMMENT '父部门ID，0=根',
    `ancestors`      VARCHAR(512) NOT NULL DEFAULT '0' COMMENT '祖级路径，逗号分隔，如 0,1,5（物化路径）',
    `dept_name`      VARCHAR(64)  NOT NULL COMMENT '部门名称',
    `sort`           INT          NOT NULL DEFAULT 0 COMMENT '显示顺序',
    `leader_user_id` BIGINT       NULL COMMENT '负责人用户ID',
    `phone`          VARCHAR(32)  NULL COMMENT '联系电话',
    `email`          VARCHAR(128) NULL COMMENT '邮箱',
    `status`         VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE' COMMENT '状态：ACTIVE/DISABLED',
    `remark`         VARCHAR(500) NULL COMMENT '备注',
    `create_by`      BIGINT       NULL COMMENT '创建人',
    `create_time`    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间（UTC）',
    `update_by`      BIGINT       NULL COMMENT '更新人',
    `update_time`    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间（UTC）',
    `del_flag`       BIGINT       NOT NULL DEFAULT 0 COMMENT '逻辑删除：0=正常；删除时写入本行id，使唯一键可重复创建',
    `version`        INT          NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    PRIMARY KEY (`id`),
    KEY `idx_dept_tenant_parent` (`tenant_id`, `parent_id`, `del_flag`),
    -- ancestors 前缀匹配是数据权限（DEPT_AND_CHILD）的热路径。
    -- 这里用前缀长度 191 而非 255：utf8mb4 下单列索引键长上限 3072 字节，
    -- 且前缀索引已足够支撑 `LIKE '0,1,5,%'` 这类左侧定长匹配。
    KEY `idx_dept_ancestors` (`tenant_id`, `ancestors`(191))
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT ='部门（树形，ancestors 物化路径）';

CREATE TABLE IF NOT EXISTS `org_post`
(
    `id`          BIGINT       NOT NULL COMMENT '主键（雪花ID）',
    `tenant_id`   BIGINT       NOT NULL DEFAULT 0 COMMENT '租户ID，0=平台级',
    `post_code`   VARCHAR(64)  NOT NULL COMMENT '岗位编码，租户内唯一',
    `post_name`   VARCHAR(64)  NOT NULL COMMENT '岗位名称',
    `sort`        INT          NOT NULL DEFAULT 0 COMMENT '显示顺序',
    `status`      VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE' COMMENT '状态：ACTIVE/DISABLED',
    `remark`      VARCHAR(500) NULL COMMENT '备注',
    `create_by`   BIGINT       NULL COMMENT '创建人',
    `create_time` DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间（UTC）',
    `update_by`   BIGINT       NULL COMMENT '更新人',
    `update_time` DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间（UTC）',
    `del_flag`    BIGINT       NOT NULL DEFAULT 0 COMMENT '逻辑删除：0=正常；删除时写入本行id，使唯一键可重复创建',
    `version`     INT          NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_post_tenant_code` (`tenant_id`, `post_code`, `del_flag`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT ='岗位';

CREATE TABLE IF NOT EXISTS `iam_user_post`
(
    `tenant_id`   BIGINT      NOT NULL DEFAULT 0 COMMENT '租户ID，与用户保持一致',
    `user_id`     BIGINT      NOT NULL COMMENT '用户ID',
    `post_id`     BIGINT      NOT NULL COMMENT '岗位ID',
    `create_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间（UTC）',
    PRIMARY KEY (`user_id`, `post_id`),
    KEY `idx_user_post_tenant_post` (`tenant_id`, `post_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT ='用户-岗位关联';
