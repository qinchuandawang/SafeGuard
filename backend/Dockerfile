# SafeGuard 后端 Dockerfile
# 构建阶段
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn dependency:go-offline -B
COPY src ./src
RUN mvn package -DskipTests -B

# 运行阶段
FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /app/target/SafeGuard-0.0.1-SNAPSHOT.jar /app/app.jar
RUN mkdir -p /app/data/memory /app/data/uploads

EXPOSE 8080

ENV SPRING_PROFILES_ACTIVE=prod \
    DB_URL=jdbc:mysql://db:3306/safeguard?useSSL=false&serverTimezone=Asia/Shanghai&characterEncoding=UTF-8&allowPublicKeyRetrieval=true \
    DB_USERNAME=root \
    DB_PASSWORD=root123 \
    DEEPSEEK_API_KEY="" \
    SILICONFLOW_API_KEY="" \
    WECHAT_APPID="" \
    WECHAT_SECRET=""

ENTRYPOINT ["java", "-Xmx256m", "-Xms128m", "-jar", "app.jar"]
