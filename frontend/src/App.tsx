import { BrowserRouter, Routes, Route, Navigate } from 'react-router'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { AuthProvider, ProtectedRoute, CallbackPage, useAuth } from '@/auth'
import { AdminLayout } from '@/admin/AdminLayout'
import { AuditLogsPage } from '@/admin/AuditLogsPage'
import { TeamsPage } from '@/admin/TeamsPage'
import { TeamDetailPage } from '@/admin/TeamDetailPage'
import { UsersPage } from '@/admin/UsersPage'
import { BrowserLayout } from '@/browser/BrowserLayout'
import { CatalogPage } from '@/browser/CatalogPage'
import { SkillDetailPage } from '@/browser/SkillDetailPage'
import { UnauthorizedPage } from '@/pages/UnauthorizedPage'

const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      staleTime: 30_000,
      retry: 1,
    },
  },
})

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
    <QueryClientProvider client={queryClient}>
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
              <Route path="/catalog" element={<BrowserLayout />}>
                <Route index element={<CatalogPage />} />
                <Route path="skills/:skillId" element={<SkillDetailPage />} />
              </Route>
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
    </QueryClientProvider>
  )
}

export default App
