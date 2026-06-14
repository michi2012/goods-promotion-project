FROM eclipse-temurin:21-jre-jammy

WORKDIR /app
COPY build/libs/*SNAPSHOT.jar app.jar

ENTRYPOINT ["java", \
  "-XX:MaxRAMPercentage=75.0", \
  "-XX:+UseParallelGC", \
  "-jar", "app.jar"]
