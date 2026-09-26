-- =====================================================================
-- AI 调研限界上下文（支撑域，表前缀 srvy_）
--
-- 设计要点：
--   1. 普通业务表，**不进租户拦截器忽略名单** → tenant_id 由拦截器自动施加，
--      因此下面的查询层不需要像 plt_dict_* / plt_config 那样手写 tenant_id 条件。
--   2. collect / report 存 task_id 的同时冗余 task_name：
--      任务名是"当时叫什么"的快照（报告是对某次任务的记录），
--      且避免列表页为了显示名字引入 JOIN —— 与 iam_user 用 JOIN 取部门名的取舍不同，
--      因为部门名会改名且需要实时，任务名在报告语境下就是历史事实。
--   3. 富文本内容用 LONGTEXT：问卷/提纲/报告正文是 HTML（含内联样式），
--      长度不可预估，用 TEXT(64KB) 会在含大量内联样式时溢出。
-- =====================================================================

-- ---------------------------------------------------------------------
-- 调研任务
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `srvy_task`
(
    `id`          BIGINT       NOT NULL COMMENT '主键（雪花ID）',
    `tenant_id`   BIGINT       NOT NULL DEFAULT 0 COMMENT '租户ID，0=平台级',
    `task_code`   VARCHAR(64)  NOT NULL COMMENT '任务编码',
    `task_name`   VARCHAR(128) NOT NULL COMMENT '任务名称',
    `task_type`   VARCHAR(32)  NOT NULL DEFAULT 'SURVEY' COMMENT '任务类型（字典 srvy_task_type）',
    `priority`    VARCHAR(16)  NOT NULL DEFAULT 'MEDIUM' COMMENT '优先级（字典 srvy_priority）',
    `owner_name`  VARCHAR(64)  NULL COMMENT '负责人',
    `dept_id`     BIGINT       NULL COMMENT '负责部门ID',
    `start_date`  DATE         NULL COMMENT '计划开始日期',
    `end_date`    DATE         NULL COMMENT '计划结束日期',
    `progress`    INT          NOT NULL DEFAULT 0 COMMENT '进度百分比 0~100',
    `status`      VARCHAR(16)  NOT NULL DEFAULT 'PENDING' COMMENT '任务状态（字典 srvy_task_status）',
    `remark`      VARCHAR(500) NULL COMMENT '备注',
    `create_by`   BIGINT       NULL COMMENT '创建人',
    `create_time` DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间（UTC）',
    `update_by`   BIGINT       NULL COMMENT '更新人',
    `update_time` DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间（UTC）',
    `del_flag`    BIGINT       NOT NULL DEFAULT 0 COMMENT '逻辑删除：0=正常；删除时写入本行id',
    `version`     INT          NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_srvy_task_tenant_code` (`tenant_id`, `task_code`, `del_flag`),
    KEY `idx_srvy_task_query` (`tenant_id`, `status`, `task_type`, `del_flag`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT ='调研任务';

-- ---------------------------------------------------------------------
-- 问卷 / 提纲（富文本正文）
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `srvy_paper`
(
    `id`          BIGINT       NOT NULL COMMENT '主键（雪花ID）',
    `tenant_id`   BIGINT       NOT NULL DEFAULT 0 COMMENT '租户ID，0=平台级',
    `paper_code`  VARCHAR(64)  NOT NULL COMMENT '问卷/提纲编码',
    `title`       VARCHAR(200) NOT NULL COMMENT '标题',
    `paper_type`  VARCHAR(16)  NOT NULL DEFAULT 'QUESTIONNAIRE' COMMENT '类型（字典 srvy_paper_type）',
    `version_no`  INT          NOT NULL DEFAULT 1 COMMENT '版本号',
    `content`     LONGTEXT     NULL COMMENT '正文（富文本 HTML）',
    `status`      VARCHAR(16)  NOT NULL DEFAULT 'DRAFT' COMMENT '状态（字典 srvy_paper_status）',
    `remark`      VARCHAR(500) NULL COMMENT '备注',
    `create_by`   BIGINT       NULL COMMENT '创建人',
    `create_time` DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间（UTC）',
    `update_by`   BIGINT       NULL COMMENT '更新人',
    `update_time` DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间（UTC）',
    `del_flag`    BIGINT       NOT NULL DEFAULT 0 COMMENT '逻辑删除：0=正常；删除时写入本行id',
    `version`     INT          NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_srvy_paper_tenant_code` (`tenant_id`, `paper_code`, `del_flag`),
    KEY `idx_srvy_paper_query` (`tenant_id`, `status`, `paper_type`, `del_flag`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT ='问卷与访谈提纲';

-- ---------------------------------------------------------------------
-- 数据采集
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `srvy_collect`
(
    `id`            BIGINT       NOT NULL COMMENT '主键（雪花ID）',
    `tenant_id`     BIGINT       NOT NULL DEFAULT 0 COMMENT '租户ID，0=平台级',
    `task_id`       BIGINT       NOT NULL COMMENT '所属调研任务ID',
    `task_name`     VARCHAR(128) NULL COMMENT '任务名称快照',
    `channel`       VARCHAR(16)  NOT NULL DEFAULT 'ONLINE' COMMENT '采集渠道（字典 srvy_channel）',
    `collector`     VARCHAR(64)  NULL COMMENT '采集人',
    `collect_date`  DATE         NULL COMMENT '采集日期',
    `sample_count`  INT          NOT NULL DEFAULT 0 COMMENT '计划样本量',
    `valid_count`   INT          NOT NULL DEFAULT 0 COMMENT '有效样本量',
    `quality_score` INT          NOT NULL DEFAULT 0 COMMENT '数据质量评分 0~100',
    `file_id`       BIGINT       NULL COMMENT '采集数据文件（srvy_attachment.id）',
    `file_name`     VARCHAR(255) NULL COMMENT '采集数据文件名（快照，列表直接显示）',
    `status`        VARCHAR(16)  NOT NULL DEFAULT 'COLLECTING' COMMENT '状态（字典 srvy_collect_status）',
    `remark`        VARCHAR(500) NULL COMMENT '备注',
    `create_by`     BIGINT       NULL COMMENT '创建人',
    `create_time`   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间（UTC）',
    `update_by`     BIGINT       NULL COMMENT '更新人',
    `update_time`   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间（UTC）',
    `del_flag`      BIGINT       NOT NULL DEFAULT 0 COMMENT '逻辑删除：0=正常；删除时写入本行id',
    `version`       INT          NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    PRIMARY KEY (`id`),
    KEY `idx_srvy_collect_query` (`tenant_id`, `task_id`, `status`, `del_flag`),
    KEY `idx_srvy_collect_date` (`tenant_id`, `collect_date`, `del_flag`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT ='调研数据采集记录';

-- ---------------------------------------------------------------------
-- 调研分析报告
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `srvy_report`
(
    `id`            BIGINT       NOT NULL COMMENT '主键（雪花ID）',
    `tenant_id`     BIGINT       NOT NULL DEFAULT 0 COMMENT '租户ID，0=平台级',
    `task_id`       BIGINT       NOT NULL COMMENT '所属调研任务ID',
    `task_name`     VARCHAR(128) NULL COMMENT '任务名称快照',
    `report_title`  VARCHAR(200) NOT NULL COMMENT '报告标题',
    `report_type`   VARCHAR(16)  NOT NULL DEFAULT 'SUMMARY' COMMENT '报告类型（字典 srvy_report_type）',
    `author`        VARCHAR(64)  NULL COMMENT '撰写人',
    `publish_date`  DATE         NULL COMMENT '发布日期',
    `summary`       VARCHAR(500) NULL COMMENT '摘要',
    `content`       LONGTEXT     NULL COMMENT '正文（富文本 HTML）',
    `file_id`       BIGINT       NULL COMMENT '报告附件（srvy_attachment.id）',
    `file_name`     VARCHAR(255) NULL COMMENT '报告附件名（快照）',
    `status`        VARCHAR(16)  NOT NULL DEFAULT 'DRAFT' COMMENT '状态（字典 srvy_report_status）',
    `remark`        VARCHAR(500) NULL COMMENT '备注',
    `create_by`     BIGINT       NULL COMMENT '创建人',
    `create_time`   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间（UTC）',
    `update_by`     BIGINT       NULL COMMENT '更新人',
    `update_time`   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间（UTC）',
    `del_flag`      BIGINT       NOT NULL DEFAULT 0 COMMENT '逻辑删除：0=正常；删除时写入本行id',
    `version`       INT          NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    PRIMARY KEY (`id`),
    KEY `idx_srvy_report_query` (`tenant_id`, `task_id`, `status`, `del_flag`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT ='调研分析报告';

-- ---------------------------------------------------------------------
-- 调研模板库
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `srvy_template`
(
    `id`            BIGINT       NOT NULL COMMENT '主键（雪花ID）',
    `tenant_id`     BIGINT       NOT NULL DEFAULT 0 COMMENT '租户ID，0=平台级',
    `template_code` VARCHAR(64)  NOT NULL COMMENT '模板编码',
    `template_name` VARCHAR(128) NOT NULL COMMENT '模板名称',
    `category`      VARCHAR(32)  NOT NULL DEFAULT 'NPS' COMMENT '模板分类（字典 srvy_template_category）',
    `content`       LONGTEXT     NULL COMMENT '模板正文（富文本 HTML）',
    `usage_count`   INT          NOT NULL DEFAULT 0 COMMENT '被引用次数',
    `status`        VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE' COMMENT '状态（字典 sys_status）',
    `remark`        VARCHAR(500) NULL COMMENT '备注',
    `create_by`     BIGINT       NULL COMMENT '创建人',
    `create_time`   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间（UTC）',
    `update_by`     BIGINT       NULL COMMENT '更新人',
    `update_time`   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间（UTC）',
    `del_flag`      BIGINT       NOT NULL DEFAULT 0 COMMENT '逻辑删除：0=正常；删除时写入本行id',
    `version`       INT          NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_srvy_template_tenant_code` (`tenant_id`, `template_code`, `del_flag`),
    KEY `idx_srvy_template_query` (`tenant_id`, `category`, `status`, `del_flag`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT ='调研模板库';

-- ---------------------------------------------------------------------
-- 附件（上传落盘元数据）
--
-- 文件本体按「租户 / 业务类型 / 年月」分目录存放，本表只登记元数据：
--   目录分层让单目录文件数可控（也便于按租户清理与迁移到对象存储），
--   file_path 存相对路径 —— 换存储介质（本地 → OSS/COS）时不必回改数据。
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `srvy_attachment`
(
    `id`           BIGINT       NOT NULL COMMENT '主键（雪花ID）',
    `tenant_id`    BIGINT       NOT NULL DEFAULT 0 COMMENT '租户ID',
    `biz_type`     VARCHAR(32)  NOT NULL COMMENT '业务类型：COLLECT_FILE/REPORT_FILE/EDITOR_IMAGE',
    `biz_id`       BIGINT       NULL COMMENT '业务ID（可空：编辑器图片先上传后落业务）',
    `file_name`    VARCHAR(255) NOT NULL COMMENT '原始文件名',
    `file_path`    VARCHAR(512) NOT NULL COMMENT '相对存储路径，形如 1/COLLECT_FILE/2026-09/uuid.csv',
    `file_size`    BIGINT       NOT NULL DEFAULT 0 COMMENT '文件字节数',
    `content_type` VARCHAR(128) NULL COMMENT 'MIME 类型',
    `storage_type` VARCHAR(16)  NOT NULL DEFAULT 'LOCAL' COMMENT '存储介质：LOCAL/OSS/COS',
    `create_by`    BIGINT       NULL COMMENT '创建人',
    `create_time`  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间（UTC）',
    `update_by`    BIGINT       NULL COMMENT '更新人',
    `update_time`  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间（UTC）',
    `del_flag`     BIGINT       NOT NULL DEFAULT 0 COMMENT '逻辑删除：0=正常；删除时写入本行id',
    `version`      INT          NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    PRIMARY KEY (`id`),
    KEY `idx_srvy_attach_biz` (`tenant_id`, `biz_type`, `biz_id`, `del_flag`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT ='调研附件（上传落盘元数据）';
