# Troubleshooting

Common issues and their resolutions when deploying and operating Alexandria.

## OIDC Misconfiguration

### Symptom: 401 Unauthorized on all API requests

**Possible causes:**

1. **Incorrect issuer URI** — The `OIDC_ISSUER_URI` does not match the `iss` claim in your tokens.

   **Fix:** Verify the issuer URI matches exactly (including trailing slashes). Check with:
   ```bash
   curl ${OIDC_ISSUER_URI}/.well-known/openid-configuration
   ```

2. **Wrong audience / client ID** — The `OIDC_CLIENT_ID` does not match the `aud` claim.

   **Fix:** Decode a token at [jwt.io](https://jwt.io) and confirm the `aud` claim matches your configured `OIDC_CLIENT_ID`.

3. **JWKS endpoint unreachable** — The backend cannot reach the OIDC provider to validate token signatures.

   **Fix:** Ensure the backend container can resolve and connect to your OIDC provider's JWKS endpoint. Check DNS resolution and firewall rules.

### Symptom: 403 Forbidden after successful login

**Possible causes:**

1. **Missing email claim** — The JWT does not include an `email` claim.

   **Fix:** Configure your OIDC provider to include the `email` claim in access tokens. Some providers only include it in ID tokens by default.

2. **Invalid email format** — The email claim exists but is malformed.

   **Fix:** Ensure the email claim contains a properly formatted email address with `@` and a valid domain.

### Symptom: Redirect loop on login

**Possible causes:**

1. **Incorrect callback URL** — The OIDC provider doesn't have the frontend callback URL registered.

   **Fix:** Add `https://your-frontend-url/auth/callback` to the allowed redirect URIs in your OIDC provider configuration.

2. **PKCE not supported** — Your provider doesn't support the PKCE flow.

   **Fix:** Ensure your OIDC provider supports Authorization Code flow with PKCE. All modern providers do, but it may need to be explicitly enabled.

---

## Database Connection Errors

### Symptom: Backend fails to start with connection refused

**Possible causes:**

1. **PostgreSQL not ready** — The database hasn't finished starting.

   **Fix:** In Docker Compose, ensure the backend has a `depends_on` with a health check condition:
   ```yaml
   depends_on:
     postgres:
       condition: service_healthy
   ```

2. **Wrong connection string** — The `SPRING_DATASOURCE_URL` is incorrect.

   **Fix:** Verify the URL format:
   ```
   jdbc:postgresql://<host>:<port>/<database>
   ```
   Default port is `5432`. In Docker Compose, the host is the service name (`postgres`).

3. **Incorrect credentials** — Username or password mismatch.

   **Fix:** Verify `SPRING_DATASOURCE_USERNAME` and `SPRING_DATASOURCE_PASSWORD` match the PostgreSQL configuration.

### Symptom: Health check reports DOWN

**Possible causes:**

1. **Database connection pool exhausted** — All connections are in use or leaked.

   **Fix:** Check application logs for connection timeout warnings. Consider increasing the pool size or investigating slow queries.

2. **Database host unreachable** — Network partition or DNS issue.

   **Fix:** Verify network connectivity between the backend and database:
   ```bash
   docker exec <backend-container> curl -v telnet://postgres:5432
   ```

### Symptom: Flyway migration fails

**Possible causes:**

1. **Schema conflicts** — Manual database changes conflict with migration scripts.

   **Fix:** Never modify the database schema manually. Use `flyway repair` if the schema_history table is corrupted, then re-run migrations.

2. **Insufficient privileges** — The database user lacks DDL permissions.

   **Fix:** Ensure the configured database user has `CREATE`, `ALTER`, and `DROP` permissions on the target schema.

---

## Container Health Check Failures

### Symptom: Backend container marked unhealthy

**Possible causes:**

1. **Slow startup** — The application hasn't finished starting within the health check `start_period`.

   **Fix:** Increase the `start_period` in your health check configuration:
   ```yaml
   healthcheck:
     test: ["CMD", "curl", "-f", "http://localhost:8080/actuator/health"]
     interval: 10s
     timeout: 5s
     retries: 6
     start_period: 60s  # Increase if startup is slow
   ```

2. **Missing curl in container** — The container image doesn't include `curl`.

   **Fix:** Use a different health check command or ensure the base image includes `curl`:
   ```yaml
   healthcheck:
     test: ["CMD", "wget", "--spider", "-q", "http://localhost:8080/actuator/health"]
   ```

3. **Port mismatch** — The health check targets a different port than the application.

   **Fix:** Ensure the health check URL port matches the application's server port (default: 8080).

### Symptom: Frontend container returns 502 Bad Gateway

**Possible causes:**

1. **Backend not reachable** — The frontend's nginx proxy cannot connect to the backend.

   **Fix:** Verify the backend service name and port in the nginx configuration. In Docker Compose, services communicate via their service names.

2. **Backend still starting** — The frontend started before the backend is ready.

   **Fix:** Add a `depends_on` with health check condition for the backend service.

---

## Common Log Messages

| Log Level | Message | Meaning |
|-----------|---------|---------|
| ERROR | `Audit event recording failed` | Non-fatal: audit failed but the operation completed |
| ERROR | `User auto-provisioning failed` | Fatal for that request: user creation failed, returns 500 |
| WARN | `Token refresh failed` | Frontend couldn't silently refresh the token |
| INFO | `User provisioned: <email>` | New user was auto-created on first login |

## Getting Help

If you're stuck, check:

1. Backend logs: `docker compose logs backend`
2. Database logs: `docker compose logs postgres`
3. Browser console: Network tab for HTTP error details
4. OIDC provider dashboard: Token issuance and error logs
