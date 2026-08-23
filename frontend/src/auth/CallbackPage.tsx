import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router'
import { userManager } from './AuthProvider'
import { LoadingSpinner } from '@/shared/components'

/**
 * Handles the OIDC callback after the identity provider redirects back.
 * Completes the authorization code exchange and navigates to the app.
 */
export function CallbackPage() {
  const navigate = useNavigate()
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    async function handleCallback() {
      try {
        await userManager.signinRedirectCallback()
        navigate('/', { replace: true })
      } catch (err) {
        const message =
          err instanceof Error ? err.message : 'Authentication callback failed'
        setError(message)
      }
    }

    void handleCallback()
  }, [navigate])

  if (error) {
    return (
      <div className="flex min-h-screen items-center justify-center">
        <div className="text-center">
          <h1 className="text-2xl font-bold text-red-700">
            Authentication Error
          </h1>
          <p className="mt-2 text-neutral-600">{error}</p>
          <a
            href="/"
            className="mt-4 inline-block text-primary-700 underline hover:text-primary-800"
          >
            Return to home
          </a>
        </div>
      </div>
    )
  }

  return (
    <div className="flex min-h-screen items-center justify-center">
      <LoadingSpinner size="lg" />
      <p className="ml-3 text-neutral-600">Completing sign-in...</p>
    </div>
  )
}
