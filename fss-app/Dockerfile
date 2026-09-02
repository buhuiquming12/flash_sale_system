# syntax=docker/dockerfile:1
# =====================================================================
# 应用镜像。构建上下文是仓库根目录（要编译全部五个模块），
# 所以 compose 里写 context: . + dockerfile: fss-app/Dockerfile。
#
# 分两段：build 段带 Maven 与 JDK（约 500MB），运行段只留 JRE。
# 不分段的话镜像里会留着整个本地仓库和编译器，体积差一个数量级。
#
# 需要 BuildKit（Docker Desktop 与 compose v2 默认就是），下面的
# --mount=type=cache 靠它生效。
# =====================================================================
FROM maven:3.9-eclipse-temurin-17 AS build

WORKDIR /build
COPY . .

# 用 cache mount 缓存 ~/.m2 而不是"先拷 pom 再 go-offline"那套：
# 多模块项目里 dependency:go-offline 会去远端找还没打包的兄弟模块
# （fss-biz 依赖 fss-domain），构建直接失败——这是它的已知行为，
# 加 -DexcludeGroupIds 也只是碰运气。cache mount 没有这个问题：
# mvn package 在 reactor 里按依赖顺序构建，本来就不需要预装兄弟模块，
# 而依赖 jar 留在 builder 的缓存里，改代码重建不会重新下载。
#
# 跳过测试：集成测试要 Testcontainers 起 MySQL/Redis/RocketMQ，
# 在镜像构建里套一层 Docker 得挂 docker.sock，不值得。
# 测试用 mvn verify 在宿主机跑，见根 README「运行测试」
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B clean package -DskipTests

# ---------------------------------------------------------------------
FROM eclipse-temurin:17-jre-jammy

# curl 只为 healthcheck 装。没有它 compose 的 depends_on: service_healthy
# 就没法用，前端会在应用还在启动时就开始打 /api 并全部失败
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl tzdata \
    && rm -rf /var/lib/apt/lists/*

# 不用 root 跑应用。这个镜像里没有任何需要特权的动作
RUN useradd -r -u 10001 -m -d /app fss
WORKDIR /app
USER fss

COPY --from=build --chown=fss:fss /build/fss-app/target/fss-app.jar /app/app.jar

# 时区必须与 MySQL 的 --default-time-zone=+08:00 一致。
# 容器默认 UTC，不设的话应用写入的 LocalDateTime 比 MySQL 的 NOW(3)
# 早 8 小时——表现是订单创建时间对不上、expire_time 的比较得出错误结论。
# 这条链路有回归测试保护（DatabaseTimezoneTest），但那是在宿主机跑的，
# 容器时区配错了测试抓不到
ENV TZ=Asia/Shanghai
ENV JAVA_OPTS="-Xms512m -Xmx1g -XX:+UseG1GC -Duser.timezone=Asia/Shanghai -Dfile.encoding=UTF-8"

EXPOSE 8080

# start-period 给到 90s：首次启动要建连接池、预热 Redis、注册消费者，
# 给短了容器会在还没起完时就被判成 unhealthy
HEALTHCHECK --interval=10s --timeout=3s --start-period=90s --retries=12 \
    CMD curl -fsS http://127.0.0.1:8080/actuator/health || exit 1

# exec 让 java 成为 1 号进程，收得到 SIGTERM——
# 否则 docker stop 会等满超时再 KILL，application.yml 里配的
# graceful shutdown 就白配了
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
