---
description: "新建后端限界上下文模块：四层包骨架、表前缀、权限码命名空间、模块边界声明"
argument-hint: "[模块名，如 organization / platform / workflow]"
---

# 新建后端限界上下文模块

模块名：**$1**

请读取 `后台管理系统功能设计方案.md` 的「四、后端 DDD 架构」章节与 `.codebuddy/rules/backend-ddd-architecture.mdc`，然后按项目规范创建模块骨架。

**动手前先确认**：

1. 该模块是否已存在（`server/admin-domain/src/main/java/**/domain/$1`）
2. 该模块属于哪个复杂度级别（L1 支撑域 / L2 标准 / L3 核心域）—— 这决定是否创建聚合根与领域事件
3. 是否与现有模块职责重叠

## 需要产出的内容

### 1. 领域层（`server/admin-domain/.../domain/$1/`）

```
domain/$1/
├─ model/          # 聚合根 / 实体 / 值对象（L3 必须有充血方法；L1 可为贫血 Entity）
├─ event/          # 领域事件（仅 L3）
├─ repository/     # 仓储接口（仅接口，无实现）
└─ service/        # 领域服务（跨聚合不变量，仅 L3 需要时创建）
```

### 2. 应用层（`server/admin-application/.../application/$1/`）

```
application/$1/
├─ command/        # record 类型命令对象
├─ query/          # 查询对象
├─ dto/            # 对外 DTO / VO
├─ assembler/      # MapStruct 转换器
└─ {Name}AppService.java   # 事务边界 + 编排，禁止业务分支
```

### 3. 基础设施层（`server/admin-infrastructure/.../infrastructure/persistence/$1/`）

```
persistence/$1/
├─ {Name}PO.java            # 贫血持久化对象，@TableName
├─ {Name}Mapper.java        # MyBatis-Plus Mapper
├─ {Name}Converter.java     # MapStruct：PO ↔ 领域对象
└─ {Name}RepositoryImpl.java # 实现 domain 层定义的仓储接口
```

### 4. 用户接口层（`server/admin-interfaces/.../interfaces/rest/$1/`）

```
rest/$1/
├─ {Name}Controller.java    # 带权限注解 + 完整 springdoc 注解
├─ request/                 # 入参 + Jakarta Validation 校验
└─ response/                # 出参
```

## 必须遵守的约定

| 项 | 要求 |
|---|---|
| **表前缀** | 按模块用途确定（`iam_` / `org_` / `plt_` / `aud_` / `tls_` / `wf_`），并说明选择理由 |
| **权限码命名空间** | `{模块}:{资源}:{动作}`，如 `organization:dept:add` |
| **模块边界** | 在 `package-info.java` 声明 Spring Modulith Named Interface |
| **精确到不可少** | **L1 模块不创建 `event/` 与 `service/`**，不搞过度设计 |
| **ArchUnit 合规** | 生成后确认 `domain` 包无框架依赖 |

## 输出要求

1. 先给出**文件清单与职责说明**（表格）
2. 说明该模块的**复杂度级别判定理由**
3. 创建文件
4. **不要自动创建 Flyway 脚本** —— 提示我改用 `/backend:new-migration`
5. 完成后列出：创建的文件、下一步建议、未完成的占位项（如 `// TODO: 补充业务规则`）

## 约束

- 不要创建 HashMap 式的"万能 Manager / Utils"类
- 不要为假想需求预留扩展点
- 若该模块明显属于 L1 简单 CRUD，**直接告知我不需要 DDD 骨架**，只生成 PO + Mapper + Service + Controller
