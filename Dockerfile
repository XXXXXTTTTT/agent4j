FROM maven:3.9-eclipse-temurin-21 AS build

WORKDIR /workspace
COPY pom.xml .
COPY agent-core/pom.xml agent-core/pom.xml
COPY agent-sandbox/pom.xml agent-sandbox/pom.xml
COPY agent-rag/pom.xml agent-rag/pom.xml
COPY agent-web/pom.xml agent-web/pom.xml
COPY agent-eval/pom.xml agent-eval/pom.xml
RUN mvn -B -DskipTests dependency:go-offline

COPY agent-core agent-core
COPY agent-sandbox agent-sandbox
COPY agent-rag agent-rag
COPY agent-web agent-web
COPY agent-eval agent-eval
RUN mvn -B -DskipTests package

FROM eclipse-temurin:21-jre

WORKDIR /app
COPY --from=build /workspace/agent-web/target/agent-web-0.1.0-SNAPSHOT.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
