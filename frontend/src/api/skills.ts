import { apiClient } from './client'
import type { PageResponse } from '@/shared/types'

// --- Skill Types ---

export type LifecycleStatus =
  | 'Draft'
  | 'Published'
  | 'Team_Approved'
  | 'Company_Approved'
  | 'Deprecated'
  | 'Archived'
  | 'Blocked'

export type RiskLevel = 'low' | 'medium' | 'high' | 'restricted'

export type ApprovalState = 'pending' | 'approved' | 'rejected'

export type ExportFormat =
  | 'generic_markdown'
  | 'github_copilot'
  | 'codex'
  | 'agents_md'
  | 'cursor_rule'
  | 'kiro_steering'

export type ExportSource = 'copy' | 'download'

export interface SkillSummary {
  id: string
  name: string
  description: string | null
  ownerTeam: string | null
  lifecycleStatus: LifecycleStatus
  approvalState: ApprovalState
  tools: string[]
  riskLevel: RiskLevel
  lastUpdated: string
}

export interface SkillVariant {
  tool: string
  instructions: string
}

export interface SkillExample {
  title: string
  description: string
  input: string
  expectedOutput: string
}

export interface SkillVersionSummary {
  version: string
  createdAt: string
  createdBy: string
  changeDescription: string | null
}

export interface FeedbackSummary {
  totalRatings: number
  averageRating: number
  totalComments: number
}

export interface ReviewStatus {
  state: ApprovalState
  reviewedBy: string | null
  reviewedAt: string | null
  comments: string | null
}

export interface SkillDetail {
  id: string
  name: string
  description: string | null
  overview: string | null
  instructions: string | null
  applicability: string | null
  examples: SkillExample[]
  limitations: string | null
  riskLevel: RiskLevel
  lifecycleStatus: LifecycleStatus
  ownerTeam: string | null
  supportedTools: string[]
  variants: SkillVariant[]
  reviewStatus: ReviewStatus | null
  versionHistory: SkillVersionSummary[]
  feedbackSummary: FeedbackSummary | null
  createdAt: string
  updatedAt: string
}

// --- API Functions ---

export interface SkillSearchParams {
  query?: string
  taskType?: string
  language?: string
  framework?: string
  tool?: string
  approvalState?: string
  riskLevel?: string
  ownerTeam?: string
  visibility?: string
  page?: number
  size?: number
}

export async function searchSkills(
  params: SkillSearchParams,
): Promise<PageResponse<SkillSummary>> {
  const response = await apiClient.get<PageResponse<SkillSummary>>(
    '/api/skills',
    { params },
  )
  return response.data
}

export async function getSkill(skillId: string): Promise<SkillDetail> {
  const response = await apiClient.get<SkillDetail>(`/api/skills/${skillId}`)
  return response.data
}

export async function exportSkill(
  skillId: string,
  format: ExportFormat,
  options: { source?: ExportSource; tool?: string; version?: number } = {},
): Promise<string> {
  const response = await apiClient.get<string>(
    `/api/skills/${skillId}/export`,
    {
      params: {
        format,
        ...(options.source && { source: options.source }),
        ...(options.tool && { tool: options.tool }),
        ...(options.version != null && { version: options.version }),
      },
      responseType: 'text',
      transformResponse: [(data: string) => data],
    },
  )
  return response.data
}
