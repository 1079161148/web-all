---
description: "扫描架构红线违规：领域层框架污染、UI 库直引、手写接口类型、租户字段缺失、服务端数据入 Pinia、权限注解遗漏"
argument-hint: "[可选：指定扫描目录，默认全量]"
---

# 架构红线扫描

对下列 10 项逐一扫描，**只报告真实违规，不要报"建议"**。扫描范围：$ARGUMENTS（为空则全量扫描）。

## 扫描项

### 后端

| # | 检查项 | 判定 |
|---|---|---|
| 1 | `server/admin-domain/**` 是否 import `org.springframework.*` / `com.baomidou.*` / `jakarta.persistence.*` | 命中即违规 |
| 2 | `server/admin-domain/**` 是否依赖 `application` / `infrastructure` / `interfaces` 包 | 命中即违规 |
| 3 | 聚合根 / 值对象上是否有 `@TableName` / `@Entity` / `@Service` 等框架注解 | 命中即违规 |
| 4 | `server/admin-domain/**` 中是否直接调用 `Instant.now()` / `LocalDateTime.now()`（应注入 `Clock`） | 命中即违规 |
| 5 | `server/admin-application/**` 是否直接依赖 `infrastructure` 包 | 命中即违规 |
| 6 | 新增的写接口（`@PostMapping` / `@PutMapping` / `@DeleteMapping`）是否缺少权限注解 | 命中即违规 |
| 7 | 新增业务表（Flyway 脚本）是否缺少 `tenant_id` / `del_flag` / `version` / 审计字段 | 命中即违规 |
| 8 | 唯一索引是否未包含 `tenant_id` | 命中即违规 |

### 前端

| # | 检查项 | 判定 |
|---|---|---|
| 9 | `apps/**` 与 `packages/**`（除 `packages/ui`）是否直接 import `naive-ui` / `vxe-table` / `form-create` | 命中即违规 |
| 10 | 是否存在手写的接口 `interface`（非 `packages/api/src/generated/**`）或直接 `axios.get('/api/...')` | 命中即违规 |
| 11 | 是否把服务端数据（列表/详情/字典）放进 Pinia store | 命中即违规 |
| 12 | 是否硬编码颜色 / 间距 / 字号（未走 `packages/theme` Token） | 命中即违规 |
| 13 | `useMessage` / `useDialog` / `useNotification` 是否在 provider 外调用 | 命中即违规 |

## 执行方式

- 用代码搜索与语义分析**逐项定位**，不要凭印象下结论
- 若项目存在 ArchUnit 测试，先跑一次并纳入结果
- 对每项给出：**是否违规**、**文件路径 + 行号**、**修复方向**

## 输出格式

```
## 架构红线扫描报告

扫描范围：<目录>
结论：<通过 / N 项违规>

### 违规明细

| # | 检查项 | 文件:行 | 问题 | 修复方向 |
|---|--------|---------|------|----------|
| 1 | 领域层框架污染 | server/admin-domain/.../Tenant.java:12 | import org.springframework.stereotype.Service | 移除注解，改用 domain 层定义的 Port 接口 |

### 通过项
- #4 ✅ 未发现硬编码时间调用
- ...

### 说明
<无法确认的项，明确说明原因，不要猜测>
```

**若未发现任何违规，明确说"未发现违规"并列出已检查的项，不要为了凑内容编造问题。**
