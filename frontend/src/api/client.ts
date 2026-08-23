import axios from 'axios'
import type { AxiosError, InternalAxiosRequestConfig } from 'axios'
import { config } from '@/config'
import { getAccessToken } from '@/auth'

/**
 * Axios instance pre-configured with the Alexandria API base URL,
 * automatic token injection, and standardized error handling.
 */
export const apiClient = axios.create({
  baseURL: config.apiBaseUrl,
  headers: {
    'Content-Type': 'application/json',
  },
})

/**
 * Request interceptor: attaches Authorization Bearer token to every request.
 */
apiClient.interceptors.request.use(
  async (requestConfig: InternalAxiosRequestConfig) => {
    const token = await getAccessToken()
    if (token) {
      requestConfig.headers.Authorization = `Bearer ${token}`
    }
    return requestConfig
  },
  (error: unknown) => Promise.reject(error),
)

/**
 * Response interceptor: handles common HTTP error statuses.
 * - 401: triggers re-authentication (token expired or invalid)
 * - 403: shows permission error notification
 * - 4xx/5xx: shows generic error toast
 */
apiClient.interceptors.response.use(
  (response) => response,
  async (error: AxiosError<{ message?: string }>) => {
    const status = error.response?.status

    if (status === 401) {
      // Token expired or invalid — attempt silent refresh, then redirect to login
      const token = await getAccessToken()
      if (!token) {
        // Could not refresh — redirect to login
        window.location.href = '/auth/callback'
      }
    } else if (status === 403) {
      showErrorNotification('You do not have permission to perform this action.')
    } else if (status && status >= 400 && status < 500) {
      const message =
        error.response?.data?.message ?? 'An error occurred. Please try again.'
      showErrorNotification(message)
    } else if (status && status >= 500) {
      showErrorNotification(
        'A server error occurred. Please try again later.',
      )
    }

    return Promise.reject(error)
  },
)

/**
 * Simple notification helper. In a real app this would be wired to a toast system.
 * For now, dispatches a custom event that UI components can listen to.
 */
function showErrorNotification(message: string): void {
  window.dispatchEvent(
    new CustomEvent('alexandria:notification', {
      detail: { type: 'error', message },
    }),
  )
}
