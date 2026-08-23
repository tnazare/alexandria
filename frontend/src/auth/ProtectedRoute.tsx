import { Navigate, Outlet } from 'react-router'
import { useAuth } from './useAuth'
import { LoadingSpinner } from '@/shared/components'

interface ProtectedRouteProps {
  /**
   * Roles that are allowed to access this route.
   * If not specified, any authenticated user can access.
   */
  allowedRoles?: string[]
}

/**
 * Route wrapper that enforces authentication and optional role-based access.
 * - Redirects to OIDC login if unauthenticated
 * - Redirects to /unauthorized if role check fails
 * - Renders child routes via <Outlet /> if authorized
 */
export function ProtectedRoute({ allowedRoles }: ProtectedRouteProps) {
  const { isAuthenticated, isLoading, userRole, login } = useAuth()

  if (isLoading) {
    return (
      <div className="flex min-h-screen items-center justify-center">
        <LoadingSpinner size="lg" />
      </div>
    )
  }

  if (!isAuthenticated) {
    // Trigger OIDC login redirect
    void login()
    return (
      <div className="flex min-h-screen items-center justify-center">
        <LoadingSpinner size="lg" />
        <p className="ml-3 text-neutral-600">Redirecting to login...</p>
      </div>
    )
  }

  // If specific roles are required, check user's role
  if (allowedRoles && allowedRoles.length > 0) {
    if (!userRole || !allowedRoles.includes(userRole)) {
      return <Navigate to="/unauthorized" replace />
    }
  }

  return <Outlet />
}
