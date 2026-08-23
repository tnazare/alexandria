# OIDC Setup

Alexandria uses OpenID Connect (OIDC) for authentication. It works with any OIDC-compliant identity provider. This guide covers configuration steps for common providers.

## Requirements

Your OIDC provider must:

1. Support the Authorization Code flow with PKCE (for the frontend SPA)
2. Issue JWT access tokens with `sub` (subject) and `email` claims
3. Serve a valid `.well-known/openid-configuration` discovery document at the issuer URI

## General Configuration

Regardless of provider, you need two values:

| Setting | Description |
|---------|-------------|
| **Issuer URI** | The OIDC issuer URL (set as `OIDC_ISSUER_URI` / `VITE_OIDC_AUTHORITY`) |
| **Client ID** | The application client identifier (set as `OIDC_CLIENT_ID` / `VITE_OIDC_CLIENT_ID`) |

### Application Registration

When registering Alexandria with your provider, configure:

- **Application type**: Single-Page Application (SPA) or Web Application
- **Redirect URIs**:
  - `http://localhost:5173/auth/callback` (local development)
  - `https://your-alexandria-domain.com/auth/callback` (production)
- **Post-logout redirect URIs**:
  - `http://localhost:5173/` (local development)
  - `https://your-alexandria-domain.com/` (production)
- **Grant types**: Authorization Code (with PKCE)
- **Scopes**: `openid`, `email`, `profile`
- **Token type**: JWT (ensure access tokens are JWTs, not opaque tokens)

### Required Claims

The JWT access token must include:

| Claim | Purpose |
|-------|---------|
| `sub` | Unique user identifier — used for auto-provisioning and lookup |
| `email` | User's email address — stored in the user profile |
| `iss` | Issuer — validated against `OIDC_ISSUER_URI` |
| `aud` | Audience — validated against `OIDC_CLIENT_ID` |
| `exp` | Expiration — tokens past expiration are rejected |

## Provider-Specific Guides

### Okta

1. **Create an Application** in the Okta Admin Console:
   - Sign in to your Okta admin dashboard
   - Navigate to **Applications** → **Create App Integration**
   - Select **OIDC - OpenID Connect** and **Single-Page Application**
   - Set the redirect URIs as described above

2. **Configure Claims**:
   - Okta includes `sub` and `email` in tokens by default
   - Ensure the `email` scope is requested

3. **Get Configuration Values**:
   - **Issuer URI**: `https://your-org.okta.com/oauth2/default`
   - **Client ID**: Found on the application's General tab

```bash
OIDC_ISSUER_URI=https://your-org.okta.com/oauth2/default
OIDC_CLIENT_ID=0oa1b2c3d4e5f6g7h8i9
```

### Microsoft Entra ID (Azure AD)

1. **Register an Application** in the Azure Portal:
   - Navigate to **Microsoft Entra ID** → **App registrations** → **New registration**
   - Set the redirect URIs (type: SPA) as described above
   - Note the **Application (client) ID** and **Directory (tenant) ID**

2. **Configure Token**:
   - Navigate to **Token configuration** → **Add optional claim**
   - Add `email` to the access token
   - Under **API permissions**, add `openid`, `email`, and `profile` (Microsoft Graph)

3. **Expose an API** (for audience validation):
   - Navigate to **Expose an API** → set the Application ID URI
   - Or use the client ID as the audience

4. **Get Configuration Values**:
   - **Issuer URI**: `https://login.microsoftonline.com/{tenant-id}/v2.0`
   - **Client ID**: The Application (client) ID

```bash
OIDC_ISSUER_URI=https://login.microsoftonline.com/your-tenant-id/v2.0
OIDC_CLIENT_ID=your-application-client-id
```

### Google Workspace

1. **Create OAuth Credentials** in the Google Cloud Console:
   - Navigate to **APIs & Services** → **Credentials** → **Create Credentials** → **OAuth client ID**
   - Application type: **Web application**
   - Add authorized redirect URIs as described above

2. **Configure Consent Screen**:
   - Add scopes: `openid`, `email`, `profile`
   - Restrict to your Google Workspace domain if desired

3. **Get Configuration Values**:
   - **Issuer URI**: `https://accounts.google.com`
   - **Client ID**: Shown on the credentials page

```bash
OIDC_ISSUER_URI=https://accounts.google.com
OIDC_CLIENT_ID=your-client-id.apps.googleusercontent.com
```

::: warning
Google's access tokens are opaque by default. You may need to use ID tokens or configure your API to validate against Google's userinfo endpoint. Verify that your token includes the required claims.
:::

### OneLogin

1. **Add an Application** in the OneLogin Admin Console:
   - Navigate to **Applications** → **Add App** → search for "OpenID Connect (OIDC)"
   - Configure redirect URIs as described above

2. **Configure Parameters**:
   - Ensure `email` is mapped and included in the token

3. **Get Configuration Values**:
   - **Issuer URI**: `https://your-org.onelogin.com/oidc/2`
   - **Client ID**: Found in the application's SSO settings

```bash
OIDC_ISSUER_URI=https://your-org.onelogin.com/oidc/2
OIDC_CLIENT_ID=your-onelogin-client-id
```

### Other OIDC Providers

Any OIDC-compliant provider works with Alexandria. You need:

1. A valid discovery document at `{issuer}/.well-known/openid-configuration`
2. JWT access tokens with `sub`, `email`, `iss`, `aud`, and `exp` claims
3. Support for Authorization Code flow with PKCE

## Verification

After configuring your provider, verify the setup:

```bash
# Check the discovery document is accessible
curl https://your-issuer-uri/.well-known/openid-configuration | jq .

# Start Alexandria and check logs for OIDC configuration
docker compose up backend

# Look for: "Resolved OIDC issuer URI: ..."
# If misconfigured, you'll see: "Failed to fetch OIDC discovery document"
```

## Troubleshooting

| Symptom | Cause | Resolution |
|---------|-------|------------|
| Backend fails to start with OIDC error | Issuer URI unreachable or invalid | Verify the URL returns a valid discovery document |
| 401 on all API requests | Token audience mismatch | Ensure `OIDC_CLIENT_ID` matches the `aud` claim in the token |
| 403 "Missing or invalid email claim" | Token lacks `email` claim | Configure your provider to include `email` in the access token |
| Login redirect loop | Incorrect redirect URI | Verify redirect URIs match exactly (including protocol and port) |
| Token validation fails | Clock skew between services | Ensure container and OIDC provider clocks are synchronized |

## Security Considerations

- **PKCE**: The frontend uses Proof Key for Code Exchange to prevent authorization code interception
- **Token storage**: Access tokens are held in memory only (not localStorage) to reduce XSS exposure
- **Token refresh**: Silent refresh is attempted before token expiration; if it fails, the user is redirected to login
- **Audience validation**: The backend validates that tokens are intended for Alexandria (via the `aud` claim)
