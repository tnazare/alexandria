# Stage 1: Build with Maven
FROM eclipse-temurin:25-jdk AS build

# Install curl (needed by Maven wrapper to download Maven)
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/*

WORKDIR /app

# Copy Maven wrapper and config
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./

# Copy module POMs for dependency resolution
COPY alexandria-api/pom.xml alexandria-api/pom.xml
COPY alexandria-entities/pom.xml alexandria-entities/pom.xml
COPY alexandria-service/pom.xml alexandria-service/pom.xml

# Download dependencies (cached layer)
RUN ./mvnw dependency:go-offline -B

# Copy source code
COPY alexandria-api/src/ alexandria-api/src/
COPY alexandria-entities/src/ alexandria-entities/src/
COPY alexandria-service/src/ alexandria-service/src/

# Build the application (skip tests for Docker build)
RUN ./mvnw package -DskipTests -B

# Stage 2: Runtime
FROM eclipse-temurin:25-jre

WORKDIR /app

# Install curl for health checks
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/*

# Copy the built JAR from build stage
COPY --from=build /app/alexandria-service/target/*.jar app.jar

# Run as non-root user
RUN useradd -r -u 1001 alexandria
USER alexandria

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
