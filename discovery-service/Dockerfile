FROM gradle:8.10-jdk17 AS builder
WORKDIR /app
COPY . .
RUN gradle :discovery-service:bootJar --no-daemon -x test

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=builder /app/discovery-service/build/libs/*.jar app.jar
EXPOSE 8761
ENTRYPOINT ["java", "-jar", "app.jar"]
