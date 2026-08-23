import { apiClient } from './client'
import type {
  PageResponse,
  TeamDetailResponse,
  TeamMemberResponse,
  TeamResponse,
} from '@/shared/types'

/**
 * Create a new team.
 * Requires Org_Admin or Catalog_Admin role.
 */
export async function createTeam(
  name: string,
  description: string,
): Promise<TeamResponse> {
  const response = await apiClient.post<TeamResponse>('/api/teams', {
    name,
    description,
  })
  return response.data
}

/**
 * List teams in the organization (paginated).
 */
export async function listTeams(
  page: number,
  size: number,
): Promise<PageResponse<TeamResponse>> {
  const response = await apiClient.get<PageResponse<TeamResponse>>(
    '/api/teams',
    { params: { page, size } },
  )
  return response.data
}

/**
 * Get detailed team information including members.
 */
export async function getTeam(teamId: string): Promise<TeamDetailResponse> {
  const response = await apiClient.get<TeamDetailResponse>(
    `/api/teams/${teamId}`,
  )
  return response.data
}

/**
 * Add a member to a team.
 * Requires Org_Admin or Team_Owner role.
 */
export async function addMember(
  teamId: string,
  userId: string,
  role: string,
): Promise<TeamMemberResponse> {
  const response = await apiClient.post<TeamMemberResponse>(
    `/api/teams/${teamId}/members`,
    { userId, teamRole: role },
  )
  return response.data
}

/**
 * Remove a member from a team.
 * Requires Org_Admin or Team_Owner role.
 */
export async function removeMember(
  teamId: string,
  userId: string,
): Promise<void> {
  await apiClient.delete(`/api/teams/${teamId}/members/${userId}`)
}
