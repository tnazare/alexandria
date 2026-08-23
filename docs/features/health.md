# Health Monitoring

Alexandria exposes health and info endpoints for monitoring, load balancer checks, and container orchestration.

## Endpoints

### Health Check

```
GET /actuator/health
```

Returns the application's health status including database connectivity.

**Response (healthy):**
```json
{
  "status": "UP"
}
```

**Response (unhealthy):**
```json
{
  "status": "DOWN"
}
```

The health check includes a database connectivity probe with a 5-second timeout.

### Application Info

```
GET /actuator/info
```

Returns application metadata.

**Response:**
```json
{
  "app": {
    "name": "alexandria",
    "version": "1.0.0"
  }
}
```

## Characteristics

- **No authentication required** — Both endpoints are accessible without a Bearer token
- **Lightweight** — Designed for frequent polling by load balancers and orchestrators
- **Database-aware** — Health status reflects actual database connectivity

## Container Health Checks

In Docker Compose and container orchestration, use the health endpoint for readiness probes:

```yaml
healthcheck:
  test: ["CMD", "curl", "-f", "http://localhost:8080/actuator/health"]
  interval: 10s
  timeout: 5s
  retries: 6
  start_period: 30s
```

## Current User Check

Authenticated users can verify their session is valid with:

```
GET /api/me
```

This returns the current user's profile (ID, email, display name, role, organization). Unlike the health endpoints, this requires a valid Bearer token.

## Related

- [Troubleshooting](/troubleshooting) — Resolving health check failures
- [Deployment (Local)](/guide/deployment-local) — Docker Compose health check configuration
