import { NavLink, Outlet } from 'react-router'
import { useAuth } from '@/auth'

/**
 * Catalog-focused layout for the skill browser.
 * Provides a top navigation bar with branding and user controls,
 * then renders the catalog content below.
 */
export function BrowserLayout() {
  const { user, logout } = useAuth()

  return (
    <div className="flex min-h-screen flex-col">
      {/* Top navigation bar */}
      <header className="sticky top-0 z-10 border-b border-neutral-200 bg-white">
        <div className="mx-auto flex h-16 max-w-7xl items-center justify-between px-6">
          {/* Left: Brand + nav links */}
          <div className="flex items-center gap-6">
            <NavLink to="/catalog" className="flex items-center gap-2">
              <BookIcon className="h-6 w-6 text-primary-600" />
              <h1 className="text-lg font-bold text-neutral-900">Alexandria</h1>
            </NavLink>
            <nav className="hidden items-center gap-1 sm:flex" aria-label="Catalog navigation">
              <NavLink
                to="/catalog"
                end
                className={({ isActive }) =>
                  `rounded-md px-3 py-2 text-sm font-medium transition-colors ${
                    isActive
                      ? 'bg-primary-50 text-primary-700'
                      : 'text-neutral-600 hover:bg-neutral-100 hover:text-neutral-900'
                  }`
                }
              >
                Catalog
              </NavLink>
              <NavLink
                to="/catalog/skills"
                className={({ isActive }) =>
                  `rounded-md px-3 py-2 text-sm font-medium transition-colors ${
                    isActive
                      ? 'bg-primary-50 text-primary-700'
                      : 'text-neutral-600 hover:bg-neutral-100 hover:text-neutral-900'
                  }`
                }
              >
                Skills
              </NavLink>
            </nav>
          </div>

          {/* Right: User info */}
          <div className="flex items-center gap-4">
            <span className="text-sm text-neutral-600 truncate max-w-[200px]">
              {user?.profile?.email ?? 'User'}
            </span>
            <button
              onClick={() => void logout()}
              className="text-sm text-neutral-500 hover:text-neutral-700 underline"
            >
              Sign out
            </button>
          </div>
        </div>
      </header>

      {/* Main content */}
      <main className="flex-1 bg-neutral-50">
        <div className="mx-auto max-w-7xl px-6 py-6">
          <Outlet />
        </div>
      </main>
    </div>
  )
}

/* Book/catalog icon */
function BookIcon({ className }: { className?: string }) {
  return (
    <svg
      className={className}
      fill="none"
      viewBox="0 0 24 24"
      strokeWidth={1.5}
      stroke="currentColor"
      aria-hidden="true"
    >
      <path
        strokeLinecap="round"
        strokeLinejoin="round"
        d="M12 6.042A8.967 8.967 0 0 0 6 3.75c-1.052 0-2.062.18-3 .512v14.25A8.987 8.987 0 0 1 6 18c2.305 0 4.408.867 6 2.292m0-14.25a8.966 8.966 0 0 1 6-2.292c1.052 0 2.062.18 3 .512v14.25A8.987 8.987 0 0 0 18 18a8.967 8.967 0 0 0-6 2.292m0-14.25v14.25"
      />
    </svg>
  )
}
