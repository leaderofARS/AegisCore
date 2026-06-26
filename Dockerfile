# --- Build Stage ---
FROM eclipse-temurin:23-jdk-alpine AS builder
WORKDIR /app

# Copy wrapper and configuration scripts
COPY gradlew gradlew.bat settings.gradle.kts build.gradle.kts /app/
COPY gradle /app/gradle

# Download Gradle distribution and dependencies
RUN ./gradlew --no-daemon dependencies || true

# Copy source code and test files
COPY src /app/src
COPY tests /app/tests

# Compile and package application
RUN ./gradlew --no-daemon jar

# --- Runtime Stage ---
FROM eclipse-temurin:23-jre-alpine
WORKDIR /app

# Copy the compiled fat JAR from build stage
COPY --from=builder /app/build/libs/*.jar /app/AegisCore.jar

# Expose standard TCP/WebSocket game port, cluster port, and metrics port
EXPOSE 5000
EXPOSE 6000
EXPOSE 8080

# Run the server utilizing JVM preview flags
ENTRYPOINT ["java", "--enable-preview", "-jar", "/app/AegisCore.jar"]
