# CODEBUDDY.md

> 本项目 AI 协作入口。**详细规范见 `.codebuddy/rules/`**，本文只保留最关键的约束与导航，避免上下文膨胀。

---

## 项目是什么

企业级**多租户 SaaS 中台基座**：DDD 内核 + 契约优先 + 可插拔业务插件。

目标：在 **多租户、契约优先、Pro 组件层、可观测、测试体系** 五个维度超越市面 admin 产品（RuoYi / RuoYi-Vue-Plus / vue-pure-admin）。

**技术栈**：JDK 25 · Spring Boot 4.0 · Spring Modulith · Vue 3.5 · Vite 8 · Naive UI · vxe-table · form-create v3 · MySQL 8 · Redis 7

---

## 开始任何任务前，先做两件事

### 1. 判断复杂度级别

| 级别 | 适用 | 要求 |
|---|---|---|
| **L1 简单模式** | 支撑域 CRUD（字典、公告、配置） | 代码生成器 + 事务脚本 + Pro 组件 |
| **L2 标准模式** | 常规业务模块 | 契约优先 + Pro 组件 + 页面模板 |
| **L3 严格模式** | 核心域（租户 / 权限 / 计费 / 流程） | DDD 聚合 + 领域事件，**需架构评审** |

### 2. 涉及设计细节时先读文档，不要凭记忆发明

| 文档 | 内容 |
|---|---|
| `后台管理系统功能设计方案.md` | **唯一权威设计文档**（v4.0）：技术选型、后端 DDD 架构、功能模块清单、多租户、四维权限、OAuth2.1、数据层、契约优先、前端架构、性能工程、可观测与测试、安全、平台治理、部署、路线图、坑位、P0 验证 |

---

## 8 条红线（违反即错误，必须立即纠正）

| # | 红线 |
|---|---|
| 1 | **`admin-domain` 零框架依赖** —— 不得 import Spring / MyBatis / Jakarta |
| 2 | **`apps/**` 不得直接 import `naive-ui` / `vxe-table` / `form-create`**，只能 import `@admin/ui` |
| 3 | **禁止手写接口类型与请求函数** —— 必须用 `@admin/api` 的 OpenAPI 生成产物 |
| 4 | **租户全链路** —— 数据访问 / 缓存 Key / 文件路径 / 异步任务 / MQ 消息必须携带 `tenantId` |
| 5 | **权限后端兜底** —— 前端权限只影响体验；**新增写接口必须加权限注解** |
| 6 | **服务端数据不入 Pinia** —— 必须用 TanStack Query |
| 7 | **数据库变更必须走 Flyway** —— 禁止手工执行 SQL、禁止修改已上线迁移脚本 |
| 8 | **不造轮子** —— 先查社区方案；引入新依赖前先记录 ADR |

---

## 目录速查

```
web-all/
├─ docs/                       # 文档站（VitePress）+ ADR
├─ apps/admin/                 # 前端主应用
├─ packages/
│  ├─ ui/                      # ★ 唯一允许 import 第三方 UI 库的地方
│  ├─ api/                     # ★ 契约生成产物（禁止手改）
│  ├─ theme/                   # Design Token 唯一来源
│  ├─ plugins/                 # 约定式插件
│  └─ utils/ hooks/ directives/ locales/ config/
├─ scripts/generators/         # plop 页面/模块生成器
└─ server/                     # 后端 Maven 多模块
   ├─ admin-common/
   ├─ admin-domain/            # ★ 零框架依赖
   ├─ admin-application/
   ├─ admin-infrastructure/
   ├─ admin-interfaces/
   ├─ admin-bootstrap/         # main + Flyway 脚本
   └─ admin-test-support/
```

---

## 常用命令

| 命令 | 用途 |
|---|---|
| `/quality:check-arch` | 扫描架构红线违规（领域层污染、UI 库直引、手写类型、租户字段缺失） |
| `/quality:review [路径]` | 按评审清单审查改动 |
| `/quality:verify-p0` | 执行 P0 八项技术验证 |
| `/backend:new-module [名]` | 新建限界上下文模块（四层骨架 + 表前缀 + 权限码） |
| `/backend:new-migration [描述]` | 生成 Flyway 迁移脚本骨架 |
| `/backend:add-permission [码]` | 新增权限点（菜单 + 后端注解 + 前端指令三处同步） |
| `/frontend:new-page [模块] [类型]` | 生成页面（list / form / detail / tree-table） |
| `/frontend:gen-api` | 契约生成 + 校验前后端一致性 |

---

## 提交规范

- **Conventional Commits**：`<type>(<scope>): <中文描述>`，末尾不加句号
- type：`feat` / `fix` / `refactor` / `perf` / `docs` / `test` / `build` / `ci` / `chore` / `revert`
- **未经明确同意，不得执行 `git commit` / `git push` / 删除文件 / 修改 git 配置**

---

## 规则索引

`.codebuddy/rules/` 下的规则会在需要时自动加载。快速对照：

| 规则 | 何时生效 |
|---|---|
| `project-context` | 始终 |
| `backend-ddd-architecture` | 始终 |
| `frontend-architecture` | 始终 |
| `api-contract-first` | 始终 |
| `code-standards` | 始终 |
| `multi-tenant-and-security` | 涉及数据访问 / 权限 / 认证 / 缓存 / 文件 |
| `database-and-migration` | 建表 / 改表 / 写 SQL / 写 Mapper |
| `ui-component-policy` | 新增或封装组件 / 引入新依赖 |
| `testing-standards` | 写测试 / 实现新功能 |
| `performance-budget` | 性能优化 / 写列表页或复杂查询 |
| `code-review-checklist` | 手动 `@code-review-checklist` |
