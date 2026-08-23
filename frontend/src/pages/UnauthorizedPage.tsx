import { useAuth } from '@/auth'

/**
 * Page displayed when a user does not have sufficient permissions
 * to access the requested route.
 */
export function UnauthorizedPage() {
  const { userRole, logout } = useAuth()

  return (
    <div className="flex min-h-screen items-center justify-center bg-neutral-50">
      <div className="max-w-md text-center">
        <div className="mx-auto mb-6 flex h-16 w-16 items-center justify-center rounded-full bg-red-100">
          <svg
            className="h-8 w-8 text-red-600"
            fill="none"
            viewBox="0 0 24 24"
            strokeWidth={1.5}
            stroke="currentColor"
            aria-hidden="true"
          >
            <path
              strokeLinecap="round"
              strokeLinejoin="round"
              d="M18.364 18.364A9 9 0 0 0 5.636 5.636m12.728 12.728A9 9 0 0 1 5.636 5.636m12.728 12.728L5.636 5.636"
            />
          </svg>
        </div>
        <h1 className="text-2xl font-bold text-neutral-900">Access Denied</h1>
        <p className="mt-3 text-neutral-600">
          You do not have the required permissions to access this page.
          {userRole && (
            <span className="block mt-1 text-sm text-neutral-500">
              Your current role: <strong>{userRole}</strong>
            </span>
          )}
        </p>
        <div className="mt-6 flex justify-center gap-4">
          <a
            href="/"
            className="rounded-md bg-primary-700 px-4 py-2 text-sm font-medium text-white hover:bg-primary-800 focus:outline-none focus:ring-2 focus:ring-primary-500 focus:ring-offset-2"
          >
            Go to Home
          </a>
          <button
            onClick={() => void logout()}
            className="rounded-md border border-neutral-300 px-4 py-2 text-sm font-medium text-neutral-700 hover:bg-neutral-50 focus:outline-none focus:ring-2 focus:ring-primary-500 focus:ring-offset-2"
          >
            Sign Out
          </button>
        </div>
      </div>
    </div>
  )
}
