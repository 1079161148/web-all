# 免费托管部署指引（Render + TiDB Cloud + Upstash）

> 目标：零成本把**完整系统**（前端 + 后端 + MySQL + Redis）部署到公网，
> 任何人打开链接即可用预填的管理员账号登录体验。
>
> ⚠️ 这是**演示级部署**：免费层有限流、休眠与无持久盘（见文末限制）。
> 要承载真实业务请升级为付费实例或自有服务器（方案见文内末尾）。

## 一、组合与分工

| 组件 | 服务商（免费层） | 作用 |
|---|---|---|
| 前端 + 后端 | Render Web Service（Docker） | 一个容器跑完：Spring Boot 吐 API + 前端静态件 |
| MySQL | TiDB Cloud Serverless | MySQL 协议兼容，Flyway 直接建表 |
| Redis | Upstash | 强制 TLS，`REDIS_SSL=true` |
| 域名 | Render 赠送 `xxx.onrender.com` | 免 HTTPS/解析 |

## 二、准备：注册并创建数据服务（约 15 分钟）

### 1. TiDB Cloud（MySQL）

1. 注册 <https://tidbcloud.com>（可用 GitHub 登录）→ 创建 **Serverless** 集群（选离你近的区域，如 Singapore）。
2. 集群页 → **Connect** → 记下四样东西：
   - Host（形如 `gateway01.ap-southeast-1.prod.aws.tidbcloud.com`）
   - Port（通常是 `4000`）
   - Username（形如 `xxxxxx.root`）
   - Password（只显示一次）
3. 数据库名自定一个（如 `webadmin`），在 TiDB 的 SQL 编辑器里执行 `CREATE DATABASE IF NOT EXISTS webadmin;`

### 2. Upstash（Redis）

1. 注册 <https://upstash.com> → Create Database → 选同区域。
2. 记下：Endpoint（形如 `xxx.upstash.io`）、Port `6379`、Password。

## 三、部署：Render（约 10 分钟）

1. 注册 <https://render.com>（GitHub 登录）→ 授权访问 `web-all` 仓库。
2. **New → Blueprint** → 选择仓库 → Render 会读取根目录的 `render.yaml` 自动创建服务。
3. 面板会提示填写所有 `sync: false` 的变量，按第二步记下的值填：

   | 变量 | 值 |
   |---|---|
   | `DB_HOST` / `DB_PORT` / `DB_NAME` | TiDB 的 host / `4000` / `webadmin` |
   | `DB_USERNAME` / `DB_PASSWORD` | TiDB 用户名 / 密码 |
   | `REDIS_HOST` / `REDIS_PASSWORD` | Upstash endpoint / 密码 |
   | `REDIS_PORT`=`6379`、`REDIS_SSL`=`true` | blueprint 已预置 |
   | `AI_API_KEY` / `AI_BASE_URL` / `AI_MODEL` | 可选，不填则 AI 对话不可用 |

4. 点 **Apply** → 首次构建约 10~20 分钟（多阶段 Docker 构建：前端 pnpm + 后端 maven）。
5. 完成后访问 `https://webadmin-demo.onrender.com`：
   - 登录页已**预填管理员账号**（`admin / Admin@123456`，构建时 `VITE_DEMO_MODE=true` 注入），
     输入图片验证码即可登录；
   - Flyway 首次启动自动建全部表。

> 每次 `git push` 到 GitHub 后，Render 自动重新构建部署 —— CI 全绿即自动上线。

## 四、免费层的边界（务必知情）

- **休眠**：15 分钟无流量实例休眠，下一个请求冷启动 30~60 秒（打开页面第一次操作会慢，属正常）。
- **无持久磁盘**：上传的附件在重启/重部署后丢失（档案表里的元数据仍在）。
- **带宽/构建时长配额**：Render 免费层每月 100GB 流量、750 实例小时。
- **TiDB Serverless 配额**：每月行读取/写入有免费额度，演示够用。
- **演示凭据是公开的**：任何访客都能用管理员身份进去玩 —— **不要在演示站放真实数据**；
  要收回体验只需在 Render 面板改 `INIT_ADMIN_PASSWORD` 并重启（前端预填文案会与新密码不一致，
  届时访客手动输入即可）。

## 五、常见问题

- **启动即挂、日志见 Flyway 报错**：多为 TiDB 连接参数错误 —— 核对 host/port/用户名（`xxxxxx.root` 这种格式别漏前缀）。
- **Redis 连不上**：确认 `REDIS_SSL=true`（Upstash 不接受明文连接）。
- **冷启动太慢想常驻**：Render 付费层 $7/月起即不休眠；或升级为自有服务器部署（Dockerfile 通用）。
- **国内访问慢**：免费海外托管的天花板就是如此；要快需国内云（需备案域名）。

## 六、从演示走向正式（路线提示）

演示站验证通过后，正式化只需要：付费实例（不休眠）+ 自有域名（Render 支持 HTTPS 自动签发）+
持久磁盘（上传附件）+ 关闭 `VITE_DEMO_MODE` 重新构建（登录页不再预填、公开注册改为邀请制）。
部署物（Dockerfile / render.yaml / SpaWebConfig）全部复用。
