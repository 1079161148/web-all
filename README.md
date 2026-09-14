# web-all

企业级**多租户 SaaS 中台基座**（DDD 内核 + 契约优先 + 可插拔业务插件）。

**技术基线**：JDK 25 LTS · Spring Boot 4.0 · Vue 3.5 · Vite 8 · Naive UI · MyBatis-Plus · Flyway

---

## 文档

| 文档 | 说明 |
|---|---|
| [后台管理系统功能设计方案.md](./后台管理系统功能设计方案.md) | **唯一权威设计文档**（v4.0，已合并历史 v1 / v2 / v3） |
| [CODEBUDDY.md](./CODEBUDDY.md) | AI 协作入口：8 条红线、目录速查、命令索引 |
| `.codebuddy/rules/` | 项目规则（11 条，团队共享，受版本控制） |
| `.codebuddy/commands/` | 自定义命令（8 个） |

## 自定义命令

| 命令 | 用途 |
|---|---|
| `/quality:check-arch` | 扫描架构红线违规 |
| `/quality:review [路径]` | 按评审清单审查改动 |
| `/quality:verify-p0` | 执行 P0 八项技术验证 |
| `/backend:new-module [名]` | 新建限界上下文模块 |
| `/backend:new-migration [描述]` | 生成 Flyway 迁移脚本 |
| `/backend:add-permission [码]` | 新增权限点（四处同步） |
| `/frontend:new-page [模块] [类型]` | 生成页面 |
| `/frontend:gen-api` | 契约生成与一致性校验 |

---

## 快速开始

### 1. 准备本地依赖

| 依赖 | 方案 | 地址 |
|---|---|---|
| MySQL | **本机原生**（服务 `MySQL84`） | `localhost:3306`，`root/123456` |
| Redis 8 | **本项目 Docker 容器** `webadmin-redis` | `localhost:6381` |
| MinIO | Docker（P1 需要对象存储时再起） | API `9000`，控制台 `9001` |

```powershell
docker compose up -d redis                          # P0 需要
docker compose --profile storage up -d redis minio  # P1 需要对象存储时
```

> ⚠️ **为什么 Redis 是 6381 而不是 6379**
>
> 本机 6379 被**另一个项目**（`E:\front-backend-web\ecommerce`）的容器占用，
> 6380 被原生 Windows Redis 占用。本项目的 compose 使用**独立项目名 + 独立端口 +
> 独立数据卷**，绝不与其他项目共用一个 Redis 实例 —— 共用会导致键空间互相覆盖、
> `FLUSHDB` 误伤他人数据、生命周期互相牵扯，且故障极难定位。
>
> 查看本机容器归属：`docker ps` 与 `docker compose ls`。

**先创建数据库**（首次执行一次）：

```powershell
$env:MYSQL_PWD='123456'
mysql -u root -e "CREATE DATABASE IF NOT EXISTS webadmin DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;"
```

**本地配置**：`application-local.yml` 已按本机环境生成（该文件已 gitignore，密码不会入库）。
团队其他成员从模板复制一份即可：

```powershell
cd server\admin-bootstrap\src\main\resources
Copy-Item application-local.yml.example application-local.yml
# 按需修改数据库密码与 Redis 端口
```

<details>
<summary>本机没有 MySQL？改用 Docker（可选）</summary>

```powershell
docker compose --profile fullstack up -d mysql
```

MySQL 容器映射到 **3307**（避开原生 3306），密码为 `root`（非原生的 `123456`），
启用后需同步修改 `application-local.yml` 的 `DB_PORT` 与密码。
</details>

### 2. 启动后端

```bash
cd server
mvn clean install -DskipTests        # 或 -Djava.version=21（本机为 JDK 21 时）
mvn -pl admin-bootstrap spring-boot:run
```

- 接口文档：http://localhost:8080/swagger-ui.html
- 健康检查：http://localhost:8080/actuator/health

**环境说明（本机实测结论）**

本机已装 3 个 JDK，**本项目使用 JDK 25**：

| 版本 | 路径 | 用途 |
|---|---|---|
| 17 | `C:\Java\jdk-17` | 其他项目 |
| 21 | `C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot` | 当前 `JAVA_HOME`（**需切换**） |
| **25** | **`C:\Java\jdk-25`** | **本项目基线** ✅ 已验证可完整构建并通过全部测试 |

把 `JAVA_HOME` 切到 JDK 25（改完**必须重启终端/IDE**，已启动的进程持有旧环境副本）：

```powershell
[Environment]::SetEnvironmentVariable('JAVA_HOME', 'C:\Java\jdk-25', 'User')
```

> 若需在多项目间切换 JDK（不同项目要求不同版本），可改用 **Maven Toolchains**：
> 在 `~/.m2/toolchains.xml` 注册各 JDK，父 POM 用 `maven-toolchains-plugin`
> 声明所需版本，`JAVA_HOME` 就不用再改。本项目不需要，因为只需要 JDK 25 一个。

**Maven** 已装（3.9.16）但 `bin` **不在 PATH 上**，且系统 PATH 中那条 Maven 路径**被截断成无效路径**
（`rogram Files\Apache\apache-maven-3.9.16\bin`，开头丢了 `C:\P`）。修复方式见 `后台管理系统功能设计方案.md` §21.2；
临时绕过用全路径：`& "C:\Program Files\Apache\apache-maven-3.9.16\bin\mvn.cmd"`。

> ⚠️ **PowerShell 下 `-D` 参数必须加引号**：`-Djava.version=21` 会被拆成
> `Unknown lifecycle phase ".version=21"`，要写成 `"-Djava.version=21"`。

- 生成前端契约产物需要两步（插件已隔离到 profile，不会污染正常构建）：

  ```bash
  mvn -pl admin-bootstrap spring-boot:run              # 终端 1：先起应用
  mvn -pl admin-bootstrap -Popenapi verify             # 终端 2：生成 openapi.json
  ```

### 3. 启动前端

```bash
pnpm install
pnpm dev                  # → http://localhost:5173
```

> Node ≥ 22，pnpm ≥ 11。

### 4. 常用命令

```bash
pnpm build                # 类型检查 + 构建
pnpm typecheck            # 仅类型检查
pnpm lint                 # Oxlint（含架构红线校验）
pnpm gen:api              # 由 OpenAPI 生成前端类型与请求
```

---

## 目录结构

```
web-all/
├─ 后台管理系统功能设计方案.md   # ★ 唯一权威设计文档
├─ CODEBUDDY.md                 # AI 协作入口
├─ .oxlintrc.json               # Lint 配置（含 UI 库隔离红线）
├─ docker-compose.yml           # 本地依赖
├─ .codebuddy/
│  ├─ rules/                    # 项目规则 11 条
│  └─ commands/                 # 自定义命令 8 个
├─ apps/
│  └─ admin/                    # 前端主应用
├─ packages/
│  ├─ ui/                       # ★ 唯一允许 import 第三方 UI 库的地方
│  ├─ api/                      # ★ OpenAPI 契约生成产物
│  ├─ theme/                    # Design Token 唯一来源
│  └─ config/                   # 共享 tsconfig
└─ server/                      # 后端 Maven 多模块
   ├─ admin-common/             # 通用内核（零框架依赖）
   ├─ admin-domain/             # ★ 领域层（零框架依赖）
   ├─ admin-application/        # 应用层（事务边界）
   ├─ admin-infrastructure/     # 基础设施层（仓储实现 / MyBatis）
   ├─ admin-interfaces/         # 接口层（Controller / 契约）
   ├─ admin-bootstrap/          # 启动模块 + Flyway 脚本
   └─ admin-test-support/       # Testcontainers 等测试基建
```

---

## 架构约束（已可执行）

这些不是文档约定，而是**构建期会失败的物理约束**：

| 约束 | 强制手段 |
|---|---|
| 领域层零框架依赖；依赖方向不可逆 | `server/admin-bootstrap/src/test/.../ArchitectureTest.java`（ArchUnit） |
| 业务代码不得直接引入 `naive-ui` / `vxe-table` / `form-create` | `.oxlintrc.json` 的 `no-restricted-imports` |
| 数据库变更必须走 Flyway | `spring.flyway` + CI `flyway validate` |
| 前后端类型不得手写 | `pnpm gen:api` + CI 校验生成产物一致 |

验证架构约束是否被破坏：

```bash
pnpm lint                                  # 前端红线
mvn -pl admin-bootstrap test               # 后端架构测试
```

---

## 当前实现进度（P0 骨架）

**已完成**

- 后端 7 个 Maven 模块 + DDD 四层骨架，**构建通过（8 模块 SUCCESS）**
- **33 个测试全部通过**：租户聚合单元测试 23 个 + ArchUnit 架构测试 10 个
- **接口已在真实环境端到端跑通**（JDK 25 + 本地 MySQL 8.4）：
  - Flyway 迁移执行并建表 ✅
  - 分页查询 / 详情 / 创建 / 唯一性校验 / 参数校验 / 不存在资源 均返回预期结果 ✅
  - 雪花 ID 生成、审计字段填充、中文 UTF-8 均正确 ✅
  - **事务性 Outbox 生效**：`event_publication` 有落库记录，`completion_date` 已回填，监听器在独立线程异步执行 ✅
- 租户（Tenant）聚合根完整垂直切片：值对象 / 状态机 / 配额 / 领域事件 / 仓储 / 应用服务 / Controller / Flyway 脚本 / 单元测试
- 多租户：`TenantContext` 门面 + MyBatis 拦截器（含**顺序敏感**的拦截器链）+ 异步传递装饰器 + 请求过滤器
- 统一响应 `R<T>` / `PageResult<T>`、业务异常体系、全局异常处理
- 前端 Monorepo（pnpm + Turborepo）、Design Token、`@admin/ui` 隔离层、`ProTable`、状态矩阵、契约层
- 前后端构建均已**实际验证通过**，两条架构门禁均已用**反向探针**验证会真正拦截违规

> 详细的验证清单与实测踩到的 20 个坑，见设计文档 §21.2 与 §21.3。

**已知待办（P1 起，均有明确落点）**

| 待办 | 位置 |
|---|---|
| 接入 Spring Security / OAuth2.1 认证 | `admin-interfaces` + 新模块；当前登录为占位实现 |
| 为写接口补权限注解（`@PreAuthorize`） | `TenantController` 内已列 TODO 清单 |
| 数据权限拦截器（`@DataScope`） | `MybatisPlusConfig` 已预留位置（必须在分页之前） |
| 动态菜单与路由装配 | `apps/admin/src/router/index.ts` |
| 字段级权限（Jackson 序列化器脱敏） | `admin-infrastructure` |
| 分页首页 JS 超预算问题（gzip 280KB > 250KB） | `packages/ui/src/plugin.ts` |
| 产品体验层：标签页、Cmd+K、引导、a11y | 见设计文档 §11 |
