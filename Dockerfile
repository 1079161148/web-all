# =====================================================================
# 多阶段构建：前端（pnpm）+ 后端（maven）→ 单个运行时镜像
# （Render 免费层 / 任意 Docker 主机：前后端同容器，Spring Boot 吐静态件）
# =====================================================================

# ---- 阶段 1：前端 ----
FROM node:22-alpine AS web
WORKDIR /app
# pnpm 由 package.json 的 packageManager 字段经 corepack 固定版本
RUN corepack enable
# 先只拷贝清单再装依赖：源码变更不击穿依赖层缓存
COPY package.json pnpm-lock.yaml ./
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
FROM maven:3.9-eclipse-temurin-25 AS server
WORKDIR /build
COPY server/pom.xml server/pom.xml
# 各模块先拷 pom 与源码（⚠️ 必须与聚合 pom 的 <modules> 一一对应，
# 漏任何一个 reactor 都会解析失败：Could not find the selected project）
COPY server/admin-common server/admin-common
COPY server/admin-domain server/admin-domain
COPY server/admin-application server/admin-application
COPY server/admin-infrastructure server/admin-infrastructure
COPY server/admin-interfaces server/admin-interfaces
COPY server/admin-bootstrap server/admin-bootstrap
COPY server/admin-test-support server/admin-test-support
RUN mvn -B -q -DskipTests install -pl admin-bootstrap -am

# ---- 阶段 3：运行时（前端静态件 + 后端 jar 同容器）----
FROM eclipse-temurin:25-jre
WORKDIR /app
# 前端产物放独立目录：由 app.web.static-location 指向（见 SpaWebConfig）
COPY --from=web /app/apps/admin/dist /app/static
COPY --from=server /build/server/admin-bootstrap/target/admin-bootstrap-1.0.0-SNAPSHOT.jar /app/app.jar
# 免费层内存有限：JVM 按容器内存的 75% 封顶
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+UseSerialGC -Xss512k"
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
