# Multi-stage build for Spring Boot application
# Stage 1: Build
FROM maven:3.9-eclipse-temurin-21 AS builder

WORKDIR /build

# Copy pom.xml and source code
COPY pom.xml .
COPY src ./src

# Build the application with Maven
RUN mvn clean package -DskipTests

# Stage 2: Runtime
FROM eclipse-temurin:21-jre

WORKDIR /app

# Install curl for health checks
RUN apt-get update && apt-get install -y curl && rm -rf /var/lib/apt/lists/*

# Copy the built JAR from the builder stage
COPY --from=builder /build/target/demo-0.0.1-SNAPSHOT.jar app.jar

# Expose the default Spring Boot port
EXPOSE 8080

# Improved health check with longer start-period and timeout
# start-period: 90s gives the JVM time to initialize
# interval: 30s check every 30 seconds
# timeout: 10s wait up to 10 seconds for response
# retries: 3 allow 3 failed checks before marking unhealthy
HEALTHCHECK --interval=30s --timeout=10s --start-period=90s --retries=3 \
    CMD curl -f http://localhost:8080/api/hello || exit 1

# Run the Spring Boot application
ENTRYPOINT ["java", "-jar", "app.jar"]
