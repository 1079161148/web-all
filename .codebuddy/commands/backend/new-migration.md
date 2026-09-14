---
description: "生成 Flyway 迁移脚本骨架（含通用字段、租户列、索引规范、分批回填提示）"
argument-hint: "[变更描述，如 add-tenant-quota / create-org-dept-tables]"
---

# 生成 Flyway 迁移脚本

变更描述：**$ARGUMENTS**

请先读取 `.codebuddy/rules/database-and-migration.mdc` 与 `后台管理系统功能设计方案.md` 的「九、数据层设计」章节，再生成脚本。

## 第 1 步：确认必要信息（缺失则询问，不要猜）

| 项 | 需要确认 |
|---|---|
| 变更类型 | 建表 / 加列 / 加索引 / 改类型 / 数据回填 |
| 表名前缀 | `iam_` / `org_` / `plt_` / `aud_` / `tls_` / `wf_` |
| 是否破坏性 | 删列 / 改类型 / 加非空列 → 是 |
| 数据量级 | 大表（> 100 万行）需要分批回填 |
| 是否需要租户列 | 业务表一律需要；平台级表 `tenant_id DEFAULT 0` |

## 第 2 步：确定版本号

1. 读取 `server/admin-bootstrap/src/main/resources/db/migration/` 下现有脚本
2. 取**当前最大版本号并递增**
3. **禁止**复用或修改已存在的版本号

命名格式：`V{大版本}.{小版本}.{补丁}__{小写下划线描述}.sql`

## 第 3 步：生成脚本

### 建表模板（必须包含全部通用字段）

```sql
CREATE TABLE {prefix}_{name} (
  id           BIGINT       NOT NULL COMMENT '主键',
  tenant_id    BIGINT       NOT NULL DEFAULT 0 COMMENT '租户ID，0=平台级',
  -- TODO: 业务字段（每个字段必须有 COMMENT）
  create_by    BIGINT       NULL     COMMENT '创建人',
  create_time  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  update_by    BIGINT       NULL     COMMENT '更新人',
  update_time  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  del_flag     TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除 0=正常 1=删除',
  version      INT          NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  remark       VARCHAR(500) NULL,
  PRIMARY KEY (id),
  KEY idx_tenant_del (tenant_id, del_flag)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='{表说明}';
```

## 必须遵守的铁律

| # | 铁律 |
|---|---|
| 1 | **不写 `down` 脚本**（回滚靠前向修复脚本或备份恢复） |
| 2 | **DDL 与 DML 分离**脚本 |
| 3 | **唯一索引必须带 `tenant_id`**：`UNIQUE KEY uk_tenant_xxx (tenant_id, xxx, del_flag)` |
| 4 | 不使用自增主键；金额用 `DECIMAL(18,4)`；时间用 `DATETIME(3)` |
| 5 | **每个字段必须有中文 `COMMENT`** |
| 6 | 不建物理外键 |
| 7 | 大表加列 / 回填**必须分批**并注明每批行数，避免长事务锁表 |
| 8 | 大表 DDL 注明 `ALGORITHM=INPLACE` 的可行性 |

## 输出要求

1. 脚本文件路径与完整内容
2. 说明：**版本号选择理由**、**是否破坏性**、**预计执行耗时**（按数据量级估算）
3. 若为数据回填，给出分批方案与校验 SQL
4. 提示我：执行前需在 test 环境用生产数据量级验证

## 约束

- 不要修改 `server/admin-bootstrap/src/main/resources/db/migration/` 下**已存在的文件**
- 不要同时在一次脚本里做 DDL 与大批量 DML
- 若信息不足（表结构、数据量级不明），**先问我**，不要自行假设
