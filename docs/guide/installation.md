# Installation

This guide walks you through the prerequisites and setup steps for running Alexandria locally.

## Prerequisites

Before installing Alexandria, ensure you have the following tools available:

| Tool | Version | Purpose |
|------|---------|---------|
| Java | 25+ | Backend runtime (Spring Boot) |
| Maven | 3.9+ (or use included `./mvnw` wrapper) | Backend build |
| Node.js | 20+ | Frontend build and documentation site |
| npm | 10+ | Package management |
| Docker | 24+ | Container runtime |
| Docker Compose | 2.20+ | Multi-container orchestration |
| PostgreSQL | 16+ | Database (provided via Docker for local dev) |

::: tip
For local development, you don't need to install PostgreSQL separately — Docker Compose provides it automatically.
:::

## Quick Start

The fastest way to get Alexandria running locally is with Docker Compose:

```bash
# Clone the repository
git clone https://github.com/alexandria/alexandria.git
cd alexandria

# Copy the environment template and configure OIDC
cp .env.example .env
# Edit .env with your OIDC provider details (see OIDC Setup guide)

# Start all services
docker compose up --build
```

Once started:
- **Backend API**: http://localhost:8080
- **Frontend**: http://localhost:5173
- **Health check**: http://localhost:8080/actuator/health

## Manual Setup (Development)

If you prefer running services individually for development:

### 1. Start PostgreSQL

```bash
docker compose up postgres -d
```

### 2. Start the Backend

```bash
cd backend
./mvnw spring-boot:run \
  -Dspring-boot.run.arguments="\
    --spring.datasource.url=jdbc:postgresql://localhost:5432/alexandria \
    --spring.datasource.username=alexandria \
    --spring.datasource.password=alexandria \
    --oidc.issuer-uri=${OIDC_ISSUER_URI} \
    --oidc.client-id=${OIDC_CLIENT_ID}"
```

Or export environment variables first:

```bash
export SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/alexandria
export SPRING_DATASOURCE_USERNAME=alexandria
export SPRING_DATASOURCE_PASSWORD=alexandria
export OIDC_ISSUER_URI=https://your-provider.example.com
export OIDC_CLIENT_ID=your-client-id

cd backend
./mvnw spring-boot:run
```

### 3. Start the Frontend

```bash
cd frontend
npm install
npm run dev
```

The frontend dev server proxies `/api` and `/actuator` requests to `http://localhost:8080`.

### 4. Build the Documentation Site

```bash
cd docs
npm install
npm run docs:dev    # Development server with hot reload
npm run docs:build  # Production build
```

## Verifying the Installation

After starting all services, verify everything is working:

```bash
# Check backend health
curl http://localhost:8080/actuator/health
# Expected: {"status":"UP"}

# Check application info
curl http://localhost:8080/actuator/info
# Expected: {"app":{"name":"alexandria","version":"..."}}
```

## Java Version Management

Alexandria requires Java 25. We recommend using [SDKMAN!](https://sdkman.io/) for managing Java versions:

```bash
# Install SDKMAN!
curl -s "https://get.sdkman.io" | bash

# The project includes a .sdkmanrc file — SDKMAN! will auto-switch:
cd alexandria
sdk env install
```

## Next Steps

- [Configuration Reference](/guide/configuration) — environment variables and settings
- [OIDC Setup](/guide/oidc-setup) — configure your identity provider
- [Local Deployment](/guide/deployment-local) — detailed Docker Compose guide
- [AWS Deployment](/guide/deployment-aws) — production deployment with Terraform
