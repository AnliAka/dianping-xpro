# 应用运行镜像：多阶段构建，第一段复用 Dockerfile.build 的编译产物。
# 最终镜像只含 JRE 和 jar，不带 Maven 与源码。
FROM maven:3.9-eclipse-temurin-25 AS builder

WORKDIR /build
COPY pom.xml .
RUN mvn -B -e -q dependency:go-offline
COPY src ./src
RUN mvn -B -e -q clean package -DskipTests
RUN cp target/*.jar /build/app.jar

# ---- 运行阶段 ----
FROM eclipse-temurin:25-jre

# 非 root 运行，降低容器逃逸风险。
RUN groupadd -r app && useradd -r -g app -d /app -s /sbin/nologin app

WORKDIR /app

COPY --from=builder /build/app.jar /app/app.jar

# 本机私有配置的挂载点。application.yml 通过
# spring.config.import: optional:file:./config/application-local.yml 读取，
# 工作目录是 /app，因此挂到这里即可生效。
RUN mkdir -p /app/config && chown -R app:app /app

USER app

EXPOSE 8081

# 容器内 Redis / MySQL 用服务名寻址，覆盖默认的本机地址。
# 密码等敏感项由 compose 的环境变量或 config/application-local.yml 提供。
ENV SPRING_DATA_REDIS_HOST=redis \
    SPRING_DATA_REDIS_PORT=6379 \
    JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -Dfile.encoding=UTF-8"

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
