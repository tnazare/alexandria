# Authentication

Alexandria uses OpenID Connect (OIDC) for authentication. Any OIDC-compliant identity provider can be used — Okta, Microsoft Entra ID, Google Workspace, OneLogin, or others.

## How It Works

1. The user navigates to the Alexandria frontend SPA
2. The app redirects to the configured OIDC provider's authorization endpoint
3. After authenticating, the provider redirects back with an authorization code
4. The frontend exchanges the code for tokens using PKCE (Proof Key for Code Exchange)
5. The access token (JWT) is sent with every API request via the `Authorization: Bearer` header
6. The backend validates the JWT signature, issuer, audience, and expiration against the OIDC provider's JWKS endpoint

## Auto-Provisioning

When a user authenticates for the first time, Alexandria automatically provisions their account:

- Extracts `sub` (subject) and `email` claims from the JWT
- Creates a user record with the default role `Skill_Consumer`
- Associates the user with the organization
- Records an audit event for the provisioning

Subsequent logins reuse the existing user record — provisioning is idempotent.

## Email Claim Requirement

Every authenticated request must include a valid email claim in the JWT. Requests without a valid email (missing `@`, empty local part, or empty domain) are rejected with HTTP 403.

## Token Lifecycle

- Access tokens are short-lived and stored in memory (not localStorage) for security
- Silent token refresh is attempted when tokens expire
- On refresh failure, the user is redirected to re-authenticate
- Sessions are stateless on the backend — no server-side session storage

## Configuration

Set the following environment variables to configure OIDC:

| Variable | Description |
|----------|-------------|
| `OIDC_ISSUER_URI` | Your OIDC provider's issuer URL |
| `OIDC_CLIENT_ID` | The client ID registered with your provider |

See the [OIDC Setup Guide](/guide/oidc-setup) for provider-specific configuration steps.
