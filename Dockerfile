# ==================== 第一阶段：构建阶段 ====================
FROM docker.m.daocloud.io/library/maven:3.9-eclipse-temurin-21 AS build

WORKDIR /workspace

# 1. 注入 Maven 阿里云镜像
RUN mkdir -p /root/.m2 && \
    echo '<settings><mirrors><mirror><id>aliyun</id><name>Aliyun Maven</name><url>https://maven.aliyun.com/repository/public</url><mirrorOf>central</mirrorOf></mirror></mirrors></settings>' > /root/.m2/settings.xml

# 2. 直接复制完整源码（不再使用低效的 dependency:go-offline）
COPY . .

# 3. 编译打包：
# -DskipTests 和 -Dmaven.test.skip=true 跳过 Java 与 Vitest 测试
# 传入 npmRegistry 强制前端下载走淘宝/阿里镜像源
RUN mvn clean package -DskipTests -Dmaven.test.skip=true -DnpmRegistry=https://registry.npmmirror.com

# ==================== 第二阶段：运行阶段 ====================
FROM docker.m.daocloud.io/library/eclipse-temurin:21-jre

WORKDIR /app
COPY --from=build /workspace/agent-web/target/agent-web-0.1.0-SNAPSHOT.jar app.jar

ENV JAVA_OPTS="-XX:+UseG1GC -XX:MaxRAMPercentage=75.0 -Dfile.encoding=UTF-8"

EXPOSE 8080

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]