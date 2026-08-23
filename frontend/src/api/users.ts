import { apiClient } from './client'
import type {
  CurrentUserResponse,
  PageResponse,
  UserResponse,
} from '@/shared/types'

/**
 * Fetch the current authenticated user's profile.
 */
export async function getMe(): Promise<CurrentUserResponse> {
  const response = await apiClient.get<CurrentUserResponse>('/api/me')
  return response.data
}

/**
 * List users in the organization (paginated).
 * Requires Org_Admin role.
 */
export async function listUsers(
  page: number,
  size: number,
): Promise<PageResponse<UserResponse>> {
  const response = await apiClient.get<PageResponse<UserResponse>>(
    '/api/users',
    { params: { page, size } },
  )
  return response.data
}

/**
 * Change a user's role.
 * Requires Org_Admin role.
 */
export async function changeRole(
  userId: string,
  role: string,
): Promise<UserResponse> {
  const response = await apiClient.patch<UserResponse>(
    `/api/users/${userId}/role`,
    { role },
  )
  return response.data
}
