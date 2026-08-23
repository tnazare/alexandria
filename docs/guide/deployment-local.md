# Local Deployment (Docker Compose)

This guide covers running Alexandria locally using Docker Compose for development and evaluation.

## Overview

The Docker Compose setup runs three services:

| Service | Image | Port | Description |
|---------|-------|------|-------------|
| `postgres` | `postgres:16-alpine` | 5432 | PostgreSQL database |
| `backend` | Built from `./backend` | 8080 | Spring Boot API server |
| `frontend` | Built from `./frontend` | 5173 | React SPA (served via nginx) |

## Prerequisites

- Docker 24+ with Docker Compose v2.20+
- An OIDC provider configured (see [OIDC Setup](/guide/oidc-setup))
- At least 4 GB RAM available for Docker

## Getting Started

### 1. Clone and Configure

```bash
git clone https://github.com/alexandria/alexandria.git
cd alexandria

# Create environment file
cat > .env << 'EOF'
OIDC_ISSUER_URI=https://your-provider.example.com
OIDC_CLIENT_ID=your-client-id
EOF
```

### 2. Start All Services

```bash
docker compose up --build
```

On first run, this will:
1. Pull the PostgreSQL 16 image
2. Build the backend container (multi-stage Maven build)
3. Build the frontend container (npm build + nginx)
4. Start PostgreSQL and wait for it to be healthy
5. Start the backend (runs Flyway migrations on startup)
6. Start the frontend

### 3. Verify

```bash
# Wait for the backend health check to pass
curl -f http://localhost:8080/actuator/health
# {"status":"UP"}

# Open the frontend
open http://localhost:5173
```

## Docker Compose Configuration

The `docker-compose.yml` at the repository root:

```yaml
services:
  postgres:
    image: postgres:16-alpine
    environment:
      POSTGRES_DB: alexandria
      POSTGRES_USER: alexandria
      POSTGRES_PASSWORD: alexandria
    ports:
      - "5432:5432"
    volumes:
      - postgres_data:/var/lib/postgresql/data
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U alexandria"]
      interval: 5s
      timeout: 5s
      retries: 5

  backend:
    build:
      context: ./backend
      dockerfile: Dockerfile
    ports:
      - "8080:8080"
    environment:
      SPRING_DATASOURCE_URL: jdbc:postgresql://postgres:5432/alexandria
      SPRING_DATASOURCE_USERNAME: alexandria
      SPRING_DATASOURCE_PASSWORD: alexandria
      OIDC_ISSUER_URI: ${OIDC_ISSUER_URI:-http://localhost:9000}
      OIDC_CLIENT_ID: ${OIDC_CLIENT_ID:-alexandria}
    depends_on:
      postgres:
        condition: service_healthy
    healthcheck:
      test: ["CMD", "curl", "-f", "http://localhost:8080/actuator/health"]
      interval: 10s
      timeout: 5s
      retries: 6
      start_period: 30s

  frontend:
    build:
      context: ./frontend
      dockerfile: Dockerfile
    ports:
      - "5173:80"
    environment:
      VITE_API_BASE_URL: http://localhost:8080
      VITE_OIDC_AUTHORITY: ${OIDC_ISSUER_URI:-http://localhost:9000}
      VITE_OIDC_CLIENT_ID: ${OIDC_CLIENT_ID:-alexandria}
    depends_on:
      - backend

volumes:
  postgres_data:
```

## Common Operations

### View Logs

```bash
# All services
docker compose logs -f

# Specific service
docker compose logs -f backend
```

### Stop Services

```bash
# Stop and keep data
docker compose down

# Stop and remove all data (database reset)
docker compose down -v
```

### Rebuild After Code Changes

```bash
# Rebuild a specific service
docker compose up --build backend

# Rebuild everything
docker compose up --build
```

### Access the Database

```bash
# Connect via psql
docker compose exec postgres psql -U alexandria -d alexandria

# Run Flyway migration info
cd backend && ./mvnw flyway:info \
  -Dflyway.url=jdbc:postgresql://localhost:5432/alexandria \
  -Dflyway.user=alexandria \
  -Dflyway.password=alexandria
```

### Reset the Database

```bash
docker compose down -v
docker compose up -d postgres
# Wait a moment for PostgreSQL to initialize, then start backend
docker compose up backend
```

## Development Workflow

For faster iteration during development, run services individually:

```bash
# Start only PostgreSQL via Docker
docker compose up -d postgres

# Run backend directly (faster restarts, debug support)
cd backend
./mvnw spring-boot:run

# Run frontend dev server (hot reload)
cd frontend
npm run dev
```

This gives you:
- Hot reload on frontend changes
- Faster backend restarts (no container rebuild)
- IDE debugger attachment support

## Port Conflicts

If default ports are in use, override them:

```bash
# Use different ports
SERVER_PORT=9090 docker compose up backend
# Or modify docker-compose.yml ports mapping
```

| Default Port | Service | Alternative |
|--------------|---------|-------------|
| 5432 | PostgreSQL | Change `ports` mapping in compose file |
| 8080 | Backend API | Set `SERVER_PORT` environment variable |
| 5173 | Frontend | Change `ports` mapping in compose file |

## Health Checks

Docker Compose uses health checks to manage service startup order:

- **PostgreSQL**: `pg_isready` — passes when accepting connections
- **Backend**: `curl /actuator/health` — passes when Spring Boot is ready and database is connected

The backend won't start until PostgreSQL is healthy. Allow up to 60 seconds for the backend to become healthy on first startup (Flyway migrations + JVM warmup).

## Troubleshooting

| Issue | Resolution |
|-------|------------|
| Backend fails with "Connection refused" to PostgreSQL | Wait for postgres health check; check `docker compose ps` for status |
| Backend fails with OIDC error | Verify `OIDC_ISSUER_URI` in `.env` is reachable from inside Docker |
| Frontend shows blank page | Check browser console; verify `VITE_API_BASE_URL` points to accessible backend |
| Port already in use | Stop conflicting service or change port mapping |
| Build fails with OOM | Increase Docker memory allocation (4 GB minimum) |
| Database migrations fail | Check `docker compose logs backend` for Flyway errors; reset with `docker compose down -v` |
