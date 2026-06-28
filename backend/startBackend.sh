#!/bin/sh
set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

echo "Starting PostgreSQL..."
docker compose up -d --wait

echo "Building backend..."
./mvnw clean package -DskipTests -q

echo "Launching Alexandria..."
java -jar alexandria-service/target/alexandria-service-0.0.1-SNAPSHOT.jar --spring.profiles.active=local
