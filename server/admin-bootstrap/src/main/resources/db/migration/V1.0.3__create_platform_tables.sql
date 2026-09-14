-- =====================================================================
-- V1.0.3 平台表：字典 / 参数配置 / 用户偏好
--
-- 字典与参数配置都支持「平台级默认 + 租户级覆盖」两级 fallback：
--   读取顺序：租户自定义值（tenant_id=?）→ 平台默认值（tenant_id=0）
--   这是多租户影响面清单（§6.4）中「字典 & 配置」的落地方式。
--   注意：两级 fallback 必须由**查询层显式实现**（见 DictQueryPort），
--   不能指望租户拦截器 —— 拦截器只会加上 `tenant_id = ?`，那会把平台默认值过滤掉。
--   因此 plt_dict_type / plt_dict_data / plt_config 都在忽略名单中，
--   由查询层自己用 `tenant_id IN (0, ?)` 表达 fallback。
--
-- del_flag 语义同 V1.0.1（存 0 或本行 id）。
-- =====================================================================

CREATE TABLE IF NOT EXISTS `plt_dict_type`
(
    `id`          BIGINT       NOT NULL COMMENT '主键（雪花ID）',
    `tenant_id`   BIGINT       NOT NULL DEFAULT 0 COMMENT '租户ID，0=平台级默认',
    `dict_name`   VARCHAR(64)  NOT NULL COMMENT '字典名称',
    `dict_type`   VARCHAR(64)  NOT NULL COMMENT '字典类型编码，如 sys_user_sex',
    `status`      VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE' COMMENT '状态：ACTIVE/DISABLED',
    `remark`      VARCHAR(500) NULL COMMENT '备注',
    `create_by`   BIGINT       NULL COMMENT '创建人',
    `create_time` DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间（UTC）',
    `update_by`   BIGINT       NULL COMMENT '更新人',
    `update_time` DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间（UTC）',
    `del_flag`    BIGINT       NOT NULL DEFAULT 0 COMMENT '逻辑删除：0=正常；删除时写入本行id，使唯一键可重复创建',
    `version`     INT          NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_dict_type` (`tenant_id`, `dict_type`, `del_flag`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT ='字典类型';

CREATE TABLE IF NOT EXISTS `plt_dict_data`
(
    `id`          BIGINT       NOT NULL COMMENT '主键（雪花ID）',
    `tenant_id`   BIGINT       NOT NULL DEFAULT 0 COMMENT '租户ID，0=平台级默认',
    `dict_type`   VARCHAR(64)  NOT NULL COMMENT '字典类型编码（冗余字段，避免关联查询）',
    `dict_label`  VARCHAR(128) NOT NULL COMMENT '字典标签（展示值）',
    `dict_value`  VARCHAR(128) NOT NULL COMMENT '字典键值（存储值）',
    `sort`        INT          NOT NULL DEFAULT 0 COMMENT '显示顺序',
    `css_class`   VARCHAR(64)  NULL COMMENT '标签样式类，如 success/warning（前端标签色）',
    `list_class`  VARCHAR(32)  NULL COMMENT '表格回显样式',
    `is_default`  TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '是否默认选中',
    `status`      VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE' COMMENT '状态：ACTIVE/DISABLED',
    `remark`      VARCHAR(500) NULL COMMENT '备注',
    `create_by`   BIGINT       NULL COMMENT '创建人',
    `create_time` DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间（UTC）',
    `update_by`   BIGINT       NULL COMMENT '更新人',
    `update_time` DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间（UTC）',
    `del_flag`    BIGINT       NOT NULL DEFAULT 0 COMMENT '逻辑删除：0=正常；删除时写入本行id，使唯一键可重复创建',
    `version`     INT          NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    PRIMARY KEY (`id`),
    -- 字典项按 type 整批查，这个联合索引直接覆盖列表读取（含排序，避免 filesort）
    KEY `idx_dict_data_type` (`tenant_id`, `dict_type`, `sort`, `del_flag`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT ='字典数据';

CREATE TABLE IF NOT EXISTS `plt_config`
(
    `id`           BIGINT        NOT NULL COMMENT '主键（雪花ID）',
    `tenant_id`    BIGINT        NOT NULL DEFAULT 0 COMMENT '租户ID，0=平台级默认',
    `config_name`  VARCHAR(128)  NOT NULL COMMENT '参数名称',
    `config_key`   VARCHAR(128)  NOT NULL COMMENT '参数键，如 sys.user.init-password',
    `config_value` VARCHAR(1024) NOT NULL COMMENT '参数值',
    `builtin`      TINYINT(1)    NOT NULL DEFAULT 0 COMMENT '是否系统内置：1=内置，不允许删除',
    `remark`       VARCHAR(500)  NULL COMMENT '备注',
    `create_by`    BIGINT        NULL COMMENT '创建人',
    `create_time`  DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间（UTC）',
    `update_by`    BIGINT        NULL COMMENT '更新人',
    `update_time`  DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间（UTC）',
    `del_flag`     BIGINT        NOT NULL DEFAULT 0 COMMENT '逻辑删除：0=正常；删除时写入本行id，使唯一键可重复创建',
    `version`      INT           NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_config_key` (`tenant_id`, `config_key`, `del_flag`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT ='参数配置（平台默认 + 租户覆盖）';

-- 用户偏好：列设置、工作台布局等。
-- 本表**不做**逻辑删除（偏好是覆盖写，用户不会"删除"它），因此无 del_flag -
-- 这是刻意的：不是所有表都必须套用通用字段模板，模板服务于业务语义，而非反过来。
CREATE TABLE IF NOT EXISTS `plt_user_preference`
(
    `id`          BIGINT       NOT NULL COMMENT '主键（雪花ID）',
    `tenant_id`   BIGINT       NOT NULL DEFAULT 0 COMMENT '租户ID',
    `user_id`     BIGINT       NOT NULL COMMENT '用户ID',
    `pref_key`    VARCHAR(128) NOT NULL COMMENT '偏好键，如 table:iam:user:columns',
    `pref_value`  JSON         NOT NULL COMMENT '偏好值（列设置、工作台布局等）',
    `create_time` DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间（UTC）',
    `update_time` DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间（UTC）',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_pref_user_key` (`tenant_id`, `user_id`, `pref_key`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT ='用户偏好（列设置、工作台布局）';
