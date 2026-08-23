import { apiClient } from './client'
import type { AuditEventResponse, PageResponse } from '@/shared/types'

/**
 * Filter parameters for querying audit events.
 */
export interface AuditEventFilters {
  page?: number
  size?: number
  targetType?: string
  targetId?: string
  actorUserId?: string
  action?: string
  startDate?: string
  endDate?: string
}

/**
 * List audit events with optional filters (paginated).
 * Requires Org_Admin or Read_Only_Auditor role.
 */
export async function listAuditEvents(
  filters: AuditEventFilters = {},
): Promise<PageResponse<AuditEventResponse>> {
  const params: Record<string, string | number> = {}

  if (filters.page != null) params['page'] = filters.page
  if (filters.size != null) params['size'] = filters.size
  if (filters.targetType) params['target_type'] = filters.targetType
  if (filters.targetId) params['target_id'] = filters.targetId
  if (filters.actorUserId) params['actor_user_id'] = filters.actorUserId
  if (filters.action) params['action'] = filters.action
  if (filters.startDate) params['start_date'] = filters.startDate
  if (filters.endDate) params['end_date'] = filters.endDate

  const response = await apiClient.get<PageResponse<AuditEventResponse>>(
    '/api/audit-events',
    { params },
  )
  return response.data
}
