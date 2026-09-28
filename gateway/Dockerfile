FROM gradle:8.10-jdk17 AS builder
WORKDIR /app
COPY . .
RUN gradle :gateway:bootJar --no-daemon -x test

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=builder /app/gateway/build/libs/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
