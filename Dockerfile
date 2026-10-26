# ---- Build stage: Maven + JDK 25 ----
FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /workspace
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q clean package -DskipTests

# ---- Run stage: JRE 25, non-root ----
FROM eclipse-temurin:25-jre
WORKDIR /app
RUN groupadd --system app && useradd --system --gid app app
COPY --from=build /workspace/target/account-service-*.jar /app/app.jar
USER app
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
