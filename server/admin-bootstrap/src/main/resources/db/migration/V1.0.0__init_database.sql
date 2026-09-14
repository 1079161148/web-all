-- =====================================================================
-- V1.0.0 初始化数据库
--
-- 铁律（设计文档 §9.1）：
--   1. 已上线的迁移脚本永不修改，只追加
--   2. 不写 down 脚本，回滚靠新增前向修复脚本或备份恢复
--   3. DDL 与 DML 分离
--   4. 每个字段必须有 COMMENT（代码生成器与元数据驱动依赖它）
--   5. 唯一索引必须包含 tenant_id（多租户项目最易漏的一条，设计文档 §9.3）
-- =====================================================================

-- ---------------------------------------------------------------------
-- 租户表（平台级）
--
-- 说明：本表是「平台的客户清单」，因此 tenant_id 语义为「归属平台」固定为 0，
-- 真正的租户标识是 code。这也解释了为什么它是租户拦截器的忽略表之一。
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `iam_tenant`
(
    `id`                      BIGINT       NOT NULL COMMENT '主键（雪花ID）',
    `code`                    VARCHAR(32)  NOT NULL COMMENT '租户编码：6~32位小写字母数字连字符，创建后不可修改',
    `name`                    VARCHAR(64)  NOT NULL COMMENT '租户名称',
    `status`                  VARCHAR(16)  NOT NULL COMMENT '状态：PENDING/ACTIVE/SUSPENDED/EXPIRED/CLOSED',
    `plan_code`               VARCHAR(32)  NOT NULL COMMENT '套餐编码',
    `plan_name`               VARCHAR(64)  NOT NULL COMMENT '套餐名称',
    `remaining_users`         BIGINT       NOT NULL DEFAULT 0 COMMENT '剩余用户数配额',
    `remaining_storage_bytes` BIGINT       NOT NULL DEFAULT 0 COMMENT '剩余存储配额（字节）',
    `remaining_api_calls`     BIGINT       NOT NULL DEFAULT 0 COMMENT '剩余月度API调用配额',
    `expire_time`             DATETIME(3)  NOT NULL COMMENT '到期时间（UTC）',
    `remark`                  VARCHAR(500) NULL COMMENT '备注',
    `create_by`               BIGINT       NULL COMMENT '创建人',
    `create_time`             DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间（UTC）',
    `update_by`               BIGINT       NULL COMMENT '更新人',
    `update_time`             DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间（UTC）',
    `del_flag`                TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0=正常 1=删除',
    `version`                 INT          NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    PRIMARY KEY (`id`),
    -- 租户编码全局唯一。del_flag 参与联合唯一 —— 注意这只允许「一条已删除记录」，
    -- 因此本表不适用「删除后重建同名」的场景。
    -- 租户实际不做软删除（终点是 status=CLOSED），故此处采用该简化写法；
    -- 对确实需要反复软删除的业务表，请按设计文档 §9.3 采用
    -- 「del_flag 存主键值」的方案，或改为在应用层做软删前唯一性校验。
    UNIQUE KEY `uk_tenant_code` (`code`, `del_flag`),
    KEY `idx_tenant_status` (`status`),
    KEY `idx_tenant_expire_time` (`expire_time`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT ='租户（平台级）';


-- ---------------------------------------------------------------------
-- 种子数据：平台级默认租户（id 固定为 1，便于本地开发与联调）
--
-- 正式环境请通过管理后台或租户开通流程创建，不要依赖种子数据。
-- ---------------------------------------------------------------------
INSERT INTO `iam_tenant` (`id`, `code`, `name`, `status`, `plan_code`, `plan_name`,
                          `remaining_users`, `remaining_storage_bytes`, `remaining_api_calls`,
                          `expire_time`, `remark`)
VALUES (1, 'platform-default', '平台默认租户', 'ACTIVE', 'ENTERPRISE', '企业版',
        10000, 1099511627776, 100000000,
        '2099-12-31 23:59:59.000', '初始化种子数据：本地开发用，正式环境请删除');
