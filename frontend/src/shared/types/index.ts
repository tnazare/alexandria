/**
 * Shared TypeScript interfaces matching backend DTOs.
 */

// --- User DTOs ---

export interface UserResponse {
  id: string
  email: string
  displayName: string | null
  role: string
  enabled: boolean
  organizationId: string
  createdAt: string
}

export interface CurrentUserResponse {
  id: string
  email: string
  displayName: string | null
  role: string
  organizationId: string
}

// --- Team DTOs ---

export interface TeamResponse {
  id: string
  name: string
  slug: string
  description: string | null
  memberCount: number
  createdAt: string
}

export interface TeamDetailResponse {
  id: string
  name: string
  slug: string
  description: string | null
  members: TeamMemberResponse[]
  createdAt: string
}

export interface TeamMemberResponse {
  userId: string
  email: string
  displayName: string | null
  teamRole: string
  joinedAt: string
}

// --- Audit DTOs ---

export interface AuditEventResponse {
  id: string
  actorUserId: string
  actorEmail: string
  action: string
  targetType: string
  targetId: string
  metadata: Record<string, unknown>
  createdAt: string
}

// --- Common DTOs ---

export interface PageResponse<T> {
  content: T[]
  page: number
  size: number
  totalElements: number
  totalPages: number
}

export interface ErrorResponse {
  error: string
  message: string
  timestamp: string
}
