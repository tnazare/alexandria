# Configuration

Alexandria is configured entirely through environment variables. No source code modification is needed for deployment.

## Backend Environment Variables

### Database

| Variable | Required | Default | Description | Example |
|----------|----------|---------|-------------|---------|
| `SPRING_DATASOURCE_URL` | Yes | — | PostgreSQL JDBC connection URL | `jdbc:postgresql://localhost:5432/alexandria` |
| `SPRING_DATASOURCE_USERNAME` | Yes | — | Database username | `alexandria` |
| `SPRING_DATASOURCE_PASSWORD` | Yes | — | Database password | `s3cret-passw0rd` |

### OIDC Authentication

| Variable | Required | Default | Description | Example |
|----------|----------|---------|-------------|---------|
| `OIDC_ISSUER_URI` | Yes | — | OIDC identity provider issuer URL. Must serve a valid `.well-known/openid-configuration` discovery document. | `https://login.microsoftonline.com/{tenant}/v2.0` |
| `OIDC_CLIENT_ID` | Yes | — | OIDC client identifier registered with the identity provider. Used for audience validation. | `alexandria-api` |

### Server

| Variable | Required | Default | Description | Example |
|----------|----------|---------|-------------|---------|
| `SERVER_PORT` | No | `8080` | HTTP port the backend listens on | `8080` |

### Spring Boot Internals

These are standard Spring Boot properties that can be overridden if needed:

| Variable | Required | Default | Description | Example |
|----------|----------|---------|-------------|---------|
| `SPRING_PROFILES_ACTIVE` | No | `default` | Active Spring profile | `prod` |
| `SPRING_JPA_HIBERNATE_DDL_AUTO` | No | `validate` | Hibernate DDL mode (always `validate` in production) | `validate` |
| `MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE` | No | `health,info` | Exposed actuator endpoints | `health,info,prometheus` |

## Frontend Environment Variables

Frontend environment variables are prefixed with `VITE_` and baked into the static build at compile time.

| Variable | Required | Default | Description | Example |
|----------|----------|---------|-------------|---------|
| `VITE_API_BASE_URL` | No | `http://localhost:8080` | Backend API base URL for frontend HTTP requests | `https://api.alexandria.example.com` |
| `VITE_OIDC_AUTHORITY` | Yes | — | OIDC provider issuer URL (same value as `OIDC_ISSUER_URI`) | `https://login.microsoftonline.com/{tenant}/v2.0` |
| `VITE_OIDC_CLIENT_ID` | Yes | — | OIDC client identifier (same value as `OIDC_CLIENT_ID`) | `alexandria-frontend` |

::: warning
Frontend variables are embedded at build time. To change them, you must rebuild the frontend or use a runtime configuration injection pattern (e.g., `config.js` served by nginx).
:::

## Docker Compose Variables

When using Docker Compose for local development, these variables are read from a `.env` file at the repository root or exported in your shell:

| Variable | Required | Default | Description |
|----------|----------|---------|-------------|
| `OIDC_ISSUER_URI` | No | `http://localhost:9000` | Passed to both backend and frontend services |
| `OIDC_CLIENT_ID` | No | `alexandria` | Passed to both backend and frontend services |

The `docker-compose.yml` provides sensible defaults for database credentials (`alexandria`/`alexandria`) suitable for local development only.

## Example `.env` File

```bash
# .env — place at repository root for Docker Compose

# OIDC Provider
OIDC_ISSUER_URI=https://your-org.okta.com/oauth2/default
OIDC_CLIENT_ID=0oa1b2c3d4e5f6g7h8i9

# Database (only needed if overriding Docker Compose defaults)
# SPRING_DATASOURCE_URL=jdbc:postgresql://custom-host:5432/alexandria
# SPRING_DATASOURCE_USERNAME=custom_user
# SPRING_DATASOURCE_PASSWORD=custom_password
```

## Production Configuration

For production deployments on AWS:

- Pass environment variables through ECS task definitions or Kubernetes secrets
- Use AWS Secrets Manager or Parameter Store for sensitive values (passwords, client secrets)
- Never commit `.env` files containing production credentials to source control
- Set `SERVER_PORT` to match your load balancer target group configuration
- Ensure `OIDC_ISSUER_URI` is reachable from the container network

See the [AWS Deployment Guide](/guide/deployment-aws) for complete production configuration.

## Configuration Validation

On startup, the backend validates:

1. **Database connectivity** — fails fast if PostgreSQL is unreachable
2. **OIDC discovery** — fetches `.well-known/openid-configuration` from the issuer URI; fails if unreachable and no cached document exists
3. **Schema validation** — Hibernate validates JPA entities against the database schema (DDL mode: `validate`)

If any required variable is missing, the application will fail to start with a descriptive error message in the logs.
