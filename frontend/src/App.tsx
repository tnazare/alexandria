import { BrowserRouter, Routes, Route, Navigate } from 'react-router'
import { AuthProvider, ProtectedRoute, CallbackPage, useAuth } from '@/auth'
import { AdminLayout } from '@/admin/AdminLayout'
import { UnauthorizedPage } from '@/pages/UnauthorizedPage'

/**
 * Placeholder pages — will be replaced by actual implementations in later tasks.
 */
function UsersPage() {
  return <h2 className="text-xl font-semibold">Users</h2>
}

function TeamsPage() {
  return <h2 className="text-xl font-semibold">Teams</h2>
}

function TeamDetailPage() {
  return <h2 className="text-xl font-semibold">Team Detail</h2>
}

function AuditLogsPage() {
  return <h2 className="text-xl font-semibold">Audit Logs</h2>
}

function CatalogPage() {
  return <h2 className="text-xl font-semibold">Skill Catalog</h2>
}

function SkillDetailPage() {
  return <h2 className="text-xl font-semibold">Skill Detail</h2>
}

/**
 * Redirects users based on their role:
 * - Org_Admin or Catalog_Admin → /admin
 * - All others → /catalog
 */
function RoleBasedRedirect() {
  const { userRole } = useAuth()
  const adminRoles = ['Org_Admin', 'Catalog_Admin']

  if (userRole && adminRoles.includes(userRole)) {
    return <Navigate to="/admin" replace />
  }

  return <Navigate to="/catalog" replace />
}

/**
 * Root application component.
 * Sets up React Router 7 with role-gated admin routes, catalog routes,
 * auth callback, and unauthorized page.
 */
function App() {
  return (
    <BrowserRouter>
      <AuthProvider>
        <Routes>
          {/* Auth callback route — no auth required */}
          <Route path="/auth/callback" element={<CallbackPage />} />

          {/* Unauthorized page — no role gate, but must be within AuthProvider */}
          <Route path="/unauthorized" element={<UnauthorizedPage />} />

          {/* Admin routes — requires Org_Admin or Catalog_Admin */}
          <Route
            element={
              <ProtectedRoute
                allowedRoles={['Org_Admin', 'Catalog_Admin']}
              />
            }
          >
            <Route path="/admin" element={<AdminLayout />}>
              <Route index element={<Navigate to="/admin/users" replace />} />
              <Route path="users" element={<UsersPage />} />
              <Route path="teams" element={<TeamsPage />} />
              <Route path="teams/:teamId" element={<TeamDetailPage />} />
              <Route path="audit" element={<AuditLogsPage />} />
            </Route>
          </Route>

          {/* Catalog routes — any authenticated user */}
          <Route element={<ProtectedRoute />}>
            <Route path="/catalog" element={<CatalogPage />} />
            <Route
              path="/catalog/skills/:skillId"
              element={<SkillDetailPage />}
            />
          </Route>

          {/* Root redirect — send to admin if admin, otherwise catalog */}
          <Route element={<ProtectedRoute />}>
            <Route path="/" element={<RoleBasedRedirect />} />
          </Route>

          {/* Catch-all — redirect to root */}
          <Route path="*" element={<Navigate to="/" replace />} />
        </Routes>
      </AuthProvider>
    </BrowserRouter>
  )
}

export default App
