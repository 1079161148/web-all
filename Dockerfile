# =====================================================================
# 多阶段构建：前端（pnpm）+ 后端（maven）→ 单个运行时镜像
# （Render 免费层 / 任意 Docker 主机：前后端同容器，Spring Boot 吐静态件）
# =====================================================================

# ---- 阶段 1：前端 ----
FROM node:22-alpine AS web
WORKDIR /app
# pnpm 版本由 package.json 的 packageManager 字段经 corepack 固定
RUN corepack enable
# pnpm-workspace.yaml 必须拷贝：它既是 workspace 定义（没有它 --filter 失效），
# 也是 pnpm 11 的「依赖构建脚本放行清单」（缺了它 esbuild 等二进制不安装，
# vite 构建直接失败）
COPY package.json pnpm-lock.yaml pnpm-workspace.yaml ./
# 先只拷清单再装依赖：源码变更不击穿依赖层缓存
COPY apps/admin/package.json apps/admin/package.json
COPY packages/api/package.json packages/api/package.json
COPY packages/theme/package.json packages/theme/package.json
COPY packages/ui/package.json packages/ui/package.json
RUN pnpm install --frozen-lockfile
COPY apps/admin apps/admin
COPY packages packages
# VITE_DEMO_MODE=true → 登录页预填演示管理员账号（见 LoginView.vue 说明）
RUN VITE_DEMO_MODE=true pnpm --filter @admin/admin build:only

# ---- 阶段 2：后端 ----
# ⚠️ 结构约定：聚合 pom（server/pom.xml）必须落在 WORKDIR 根，各模块目录
# 与它平级 —— 否则 mvn 找不到聚合 pom，报
# "Could not find the selected project in the reactor"（实测踩过）。
# COPY 清单必须与聚合 pom 的 <modules> 一一对应（共 7 个，漏一即挂）。
FROM maven:3.9-eclipse-temurin-25 AS server
WORKDIR /build
COPY server/pom.xml pom.xml
COPY server/admin-common admin-common
COPY server/admin-domain admin-domain
COPY server/admin-application admin-application
COPY server/admin-infrastructure admin-infrastructure
COPY server/admin-interfaces admin-interfaces
COPY server/admin-bootstrap admin-bootstrap
COPY server/admin-test-support admin-test-support
RUN mvn -B -q -DskipTests install -pl admin-bootstrap -am

# ---- 阶段 3：运行时（前端静态件 + 后端 jar 同容器）----
FROM eclipse-temurin:25-jre
WORKDIR /app
# 前端产物放独立目录：由 app.web.static-location 指向（见 SpaWebConfig）
COPY --from=web /app/apps/admin/dist /app/static
COPY --from=server /build/admin-bootstrap/target/admin-bootstrap-1.0.0-SNAPSHOT.jar /app/app.jar
# 免费层内存有限：JVM 按容器内存的 75% 封顶
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+UseSerialGC -Xss512k"
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
