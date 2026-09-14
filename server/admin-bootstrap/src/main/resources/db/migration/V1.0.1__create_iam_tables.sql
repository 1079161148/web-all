-- =====================================================================
-- V1.0.1 IAM 表：用户 / 角色 / 菜单权限 / 关联关系
--
-- 严格遵守设计文档 §9.2 通用字段模板与 §9.3 索引规范：
--   · 主键 BIGINT 雪花（不自增）
--   · tenant_id 全表存在，0 = 平台级
--   · 时间统一 DATETIME(3) + UTC
--   · 唯一索引必须包含 tenant_id（多租户最易漏的一条）
--   · 不建物理外键，关系靠应用层与索引保证
--   · 每个字段必须有 COMMENT
--
-- ⚠️ 两个关键设计决策（都是实测踩过的坑，不要改回去）：
--
-- 【决策一】关联表也带 tenant_id
--   若关联表没有 tenant_id，TenantLineInnerInterceptor 会尝试给它拼
--   `tenant_id = ?` 导致 SQL 报错；若为此把它加入忽略名单，则等于给它开了
--   一道不受控的跨租户口子。加一个 tenant_id 列的成本极低（拦截器在 INSERT 时
--   会自动填充），却换来了「所有业务表隔离方式完全统一、无需任何例外」。
--   一致性 > 小聪明。
--
-- 【决策二】del_flag 存 0 或「本行主键值」，而不是 0/1
--   唯一索引含 del_flag 时，若删除固定写 1，则同一唯一键只能被软删除一次 ——
--   第二次「创建 → 删除」就会撞唯一键。IAM 里的典型场景是员工离职
--   （删除 admin_demo）→ 重新入职（再建 admin_demo），第二次必挂。
--   因此删除时把 del_flag 写成该行自己的 id（非 0 且互不相同），既保留
--   `del_flag = 0` 作为「未删除」的判据，又让唯一索引支持任意次删除重建。
--   对应配置：mybatis-plus.global-config.db-config.logic-delete-value: id
-- =====================================================================

-- ---------------------------------------------------------------------
-- 前向对齐：把 V1.0.0 的 iam_tenant.del_flag 统一到本文件确立的语义
--
-- V1.0.0 用的是 INT + 0/1，而本文件起改用 BIGINT + 「0 或本行 id」。
-- 必须对齐的原因：全局配置 logic-delete-value 是统一的（值就是 id），
-- 若某张表仍是 INT，删除时写入雪花 id（19 位）会直接溢出报错。
-- 这类"配置是全局的、列类型是局部的"不一致，只在真正执行删除时才暴露。
-- ---------------------------------------------------------------------
ALTER TABLE `iam_tenant`
    MODIFY COLUMN `del_flag` BIGINT NOT NULL DEFAULT 0 COMMENT '逻辑删除：0=正常；删除时写入本行id';

-- ---------------------------------------------------------------------
-- 用户
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `iam_user`
(
    `id`          BIGINT       NOT NULL COMMENT '主键（雪花ID）',
    `tenant_id`   BIGINT       NOT NULL DEFAULT 0 COMMENT '租户ID，0=平台级',
    `dept_id`     BIGINT       NULL COMMENT '所属部门ID',
    `username`    VARCHAR(64)  NOT NULL COMMENT '登录账号，租户内唯一',
    `nickname`    VARCHAR(64)  NOT NULL COMMENT '用户昵称/姓名',
    `password`    VARCHAR(128) NOT NULL COMMENT '密码哈希（BCrypt）',
    `email`       VARCHAR(128) NULL COMMENT '邮箱',
    `phone`       VARCHAR(32)  NULL COMMENT '手机号（字段级权限保护的敏感字段）',
    `sex`         TINYINT      NOT NULL DEFAULT 2 COMMENT '性别：0=男 1=女 2=未知',
    `avatar`      VARCHAR(512) NULL COMMENT '头像URL',
    `status`      VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE' COMMENT '状态：ACTIVE/SUSPENDED/LOCKED',
    `login_ip`    VARCHAR(64)  NULL COMMENT '最后登录IP',
    `login_time`  DATETIME(3)  NULL COMMENT '最后登录时间（UTC）',
    `fail_count`  INT          NOT NULL DEFAULT 0 COMMENT '连续登录失败次数，达阈值锁定',
    `lock_until`  DATETIME(3)  NULL COMMENT '锁定截止时间（UTC）',
    `remark`      VARCHAR(500) NULL COMMENT '备注',
    `create_by`   BIGINT       NULL COMMENT '创建人',
    `create_time` DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间（UTC）',
    `update_by`   BIGINT       NULL COMMENT '更新人',
    `update_time` DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间（UTC）',
    `del_flag`    BIGINT       NOT NULL DEFAULT 0 COMMENT '逻辑删除：0=正常；删除时写入本行id，使唯一键可重复创建',
    `version`     INT          NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_tenant_username` (`tenant_id`, `username`, `del_flag`),
    KEY `idx_user_tenant_dept` (`tenant_id`, `dept_id`, `del_flag`),
    KEY `idx_user_tenant_status` (`tenant_id`, `status`, `del_flag`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT ='用户';

-- ---------------------------------------------------------------------
-- 角色
--
-- data_scope 是四维权限中「数据权限」的载体；
-- field_policy 预留给字段级权限的规则覆盖（P1 用注解实现，P2 可下沉到此列做动态配置）
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `iam_role`
(
    `id`           BIGINT       NOT NULL COMMENT '主键（雪花ID）',
    `tenant_id`    BIGINT       NOT NULL DEFAULT 0 COMMENT '租户ID，0=平台级',
    `role_name`    VARCHAR(64)  NOT NULL COMMENT '角色名称',
    `role_key`     VARCHAR(64)  NOT NULL COMMENT '角色标识，如 SUPER_ADMIN，租户内唯一',
    `sort`         INT          NOT NULL DEFAULT 0 COMMENT '显示顺序',
    `data_scope`   VARCHAR(24)  NOT NULL DEFAULT 'SELF' COMMENT '数据范围：ALL/CUSTOM/DEPT/DEPT_AND_CHILD/SELF',
    `field_policy` JSON         NULL COMMENT '字段级权限策略覆盖（可选，为空则用注解默认值）',
    `builtin`      TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '是否内置角色：1=内置，不允许改标识与删除',
    `status`       VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE' COMMENT '状态：ACTIVE/SUSPENDED',
    `remark`       VARCHAR(500) NULL COMMENT '备注',
    `create_by`    BIGINT       NULL COMMENT '创建人',
    `create_time`  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间（UTC）',
    `update_by`    BIGINT       NULL COMMENT '更新人',
    `update_time`  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间（UTC）',
    `del_flag`     BIGINT       NOT NULL DEFAULT 0 COMMENT '逻辑删除：0=正常；删除时写入本行id，使唯一键可重复创建',
    `version`      INT          NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_role_tenant_key` (`tenant_id`, `role_key`, `del_flag`),
    KEY `idx_role_tenant_status` (`tenant_id`, `status`, `del_flag`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT ='角色';

-- ---------------------------------------------------------------------
-- 菜单 / 权限
--
-- menu_type 三态：DIR=目录，MENU=菜单，BUTTON=按钮（按钮即接口权限点）
-- perms 就是权限码，格式 {context}:{resource}:{action}，如 iam:user:add
--
-- ⚠️ 本表是**平台级共享**数据，固定 tenant_id=0，并因此列入租户拦截器的忽略名单。
--    理由：菜单结构由平台统一维护，租户之间的可见范围差异通过「租户套餐 × 角色-菜单
--    分配」来表达，而不是各租户各存一份菜单树。忽略名单是显式声明的例外，
--    不是漏配 —— 详见 TenantLineHandlerImpl 的注释。
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `iam_menu`
(
    `id`          BIGINT       NOT NULL COMMENT '主键（雪花ID）',
    `tenant_id`   BIGINT       NOT NULL DEFAULT 0 COMMENT '租户ID，固定0=平台级共享',
    `parent_id`   BIGINT       NOT NULL DEFAULT 0 COMMENT '父菜单ID，0=根',
    `menu_name`   VARCHAR(64)  NOT NULL COMMENT '菜单名称',
    `menu_type`   VARCHAR(16)  NOT NULL COMMENT '类型：DIR=目录 MENU=菜单 BUTTON=按钮',
    `path`        VARCHAR(255) NULL COMMENT '路由地址（仅 DIR/MENU）',
    `component`   VARCHAR(255) NULL COMMENT '组件路径，如 iam/user/index（前端查表映射，不硬编码）',
    `perms`       VARCHAR(128) NULL COMMENT '权限码，如 iam:user:add（仅 BUTTON）',
    `icon`        VARCHAR(64)  NULL COMMENT '图标名（前端通过 import.meta.glob 解析）',
    `sort`        INT          NOT NULL DEFAULT 0 COMMENT '显示顺序',
    `visible`     TINYINT(1)   NOT NULL DEFAULT 1 COMMENT '是否显示：1=显示 0=隐藏',
    `keep_alive`  TINYINT(1)   NOT NULL DEFAULT 1 COMMENT '是否缓存页面',
    `always_show` TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '只有一个子路由时是否仍显示父级',
    `status`      VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE' COMMENT '状态：ACTIVE/DISABLED',
    `remark`      VARCHAR(500) NULL COMMENT '备注',
    `create_by`   BIGINT       NULL COMMENT '创建人',
    `create_time` DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间（UTC）',
    `update_by`   BIGINT       NULL COMMENT '更新人',
    `update_time` DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间（UTC）',
    `del_flag`    BIGINT       NOT NULL DEFAULT 0 COMMENT '逻辑删除：0=正常；删除时写入本行id，使唯一键可重复创建',
    `version`     INT          NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    PRIMARY KEY (`id`),
    KEY `idx_menu_parent` (`parent_id`, `sort`),
    -- perms 是鉴权热路径（按权限码反查菜单），单列索引
    KEY `idx_menu_perms` (`perms`),
    KEY `idx_menu_type_status` (`menu_type`, `status`, `del_flag`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT ='菜单与权限点';

-- ---------------------------------------------------------------------
-- 关联表：用户-角色 / 角色-菜单 / 角色-部门（自定义数据范围）
--
-- 都带 tenant_id（见文件头【决策一】）。联合主键天然防重，
-- 并建反向索引支撑「按角色查用户」「按菜单查角色」这类反查。
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `iam_user_role`
(
    `tenant_id`   BIGINT      NOT NULL DEFAULT 0 COMMENT '租户ID，与用户保持一致',
    `user_id`     BIGINT      NOT NULL COMMENT '用户ID',
    `role_id`     BIGINT      NOT NULL COMMENT '角色ID',
    `create_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间（UTC）',
    PRIMARY KEY (`user_id`, `role_id`),
    KEY `idx_user_role_tenant_role` (`tenant_id`, `role_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT ='用户-角色关联';

CREATE TABLE IF NOT EXISTS `iam_role_menu`
(
    `tenant_id`   BIGINT      NOT NULL DEFAULT 0 COMMENT '租户ID，与角色保持一致',
    `role_id`     BIGINT      NOT NULL COMMENT '角色ID',
    `menu_id`     BIGINT      NOT NULL COMMENT '菜单ID',
    `create_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间（UTC）',
    PRIMARY KEY (`role_id`, `menu_id`),
    KEY `idx_role_menu_tenant_menu` (`tenant_id`, `menu_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT ='角色-菜单关联';

-- CUSTOM 数据范围时，角色可见的部门集合
CREATE TABLE IF NOT EXISTS `iam_role_dept`
(
    `tenant_id`   BIGINT      NOT NULL DEFAULT 0 COMMENT '租户ID，与角色保持一致',
    `role_id`     BIGINT      NOT NULL COMMENT '角色ID',
    `dept_id`     BIGINT      NOT NULL COMMENT '部门ID',
    `create_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间（UTC）',
    PRIMARY KEY (`role_id`, `dept_id`),
    KEY `idx_role_dept_tenant_dept` (`tenant_id`, `dept_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT ='角色-自定义数据范围部门';
