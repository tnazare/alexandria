/**
 * Runtime configuration for the Alexandria frontend.
 * Values are sourced from Vite environment variables (prefixed with VITE_).
 */
export const config = {
  /** Base URL for the Alexandria REST API */
  apiBaseUrl: import.meta.env.VITE_API_BASE_URL ?? 'http://localhost:8080',

  /** OIDC identity provider authority URL */
  oidcAuthority: import.meta.env.VITE_OIDC_AUTHORITY ?? 'http://localhost:9000',

  /** OIDC client identifier */
  oidcClientId: import.meta.env.VITE_OIDC_CLIENT_ID ?? 'alexandria',
} as const
