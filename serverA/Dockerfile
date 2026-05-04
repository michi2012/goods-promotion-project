FROM eclipse-temurin:21-jdk-alpine

WORKDIR /app
COPY build/libs/*SNAPSHOT.jar app.jar

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]