#!/bin/sh
set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

POLL_INTERVAL=3

echo "Starting PostgreSQL..."
docker compose up -d --wait

echo "Building backend..."
./mvnw compile -am -q

echo ""
echo "Starting backend with hot reload..."
echo "Polling every ${POLL_INTERVAL}s for .java/.yml/.xml/.sql changes."
echo "Press Ctrl+C to stop."
echo ""

# Start the Spring Boot app in background with devtools
./mvnw spring-boot:run \
  -pl alexandria-service \
  -am \
  -Dspring-boot.run.profiles=local \
  -Dspring-boot.run.jvmArguments="-Dspring.devtools.restart.pollInterval=2s -Dspring.devtools.restart.quietPeriod=1s" &
APP_PID=$!

# Wait for the app to start
sleep 5

# File watcher using find + polling (no external deps)
LAST_HASH=""
watch_and_compile() {
  while true; do
    CURRENT_HASH=$(find alexandria-api/src alexandria-entities/src alexandria-service/src \
      -type f \( -name "*.java" -o -name "*.yml" -o -name "*.xml" -o -name "*.sql" -o -name "*.properties" \) \
      -newer alexandria-service/target/classes 2>/dev/null | head -1)

    if [ -n "$CURRENT_HASH" ]; then
      echo ""
      echo "[watcher] Change detected, recompiling..."
      if ./mvnw compile -pl alexandria-api,alexandria-entities,alexandria-service -q 2>&1; then
        echo "[watcher] Recompilation complete. Devtools will restart."
      else
        echo "[watcher] Compilation failed. Fix errors and save again."
      fi
    fi

    sleep "$POLL_INTERVAL"
  done
}

watch_and_compile &
WATCH_PID=$!

# Cleanup on exit
cleanup() {
  echo ""
  echo "Shutting down..."
  kill $WATCH_PID 2>/dev/null || true
  kill $APP_PID 2>/dev/null || true
  wait $APP_PID 2>/dev/null || true
  wait $WATCH_PID 2>/dev/null || true
}
trap cleanup INT TERM

# Wait for app process
wait $APP_PID
