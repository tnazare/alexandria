/**
 * TypeScript types mirroring the backend skill/variant DTOs
 * (com.alexandria.skill.dto and com.alexandria.skill.variant.dto).
 *
 * These are the authoring/CRUD types used by the skill management pages.
 * The read-only browse layer keeps its own types in {@code @/api/skills}.
 */

export type LifecycleStatus =
  | 'Draft'
  | 'Published'
  | 'Team_Approved'
  | 'Company_Approved'
  | 'Deprecated'
  | 'Archived'
  | 'Blocked'

export type RiskLevel = 'low' | 'medium' | 'high' | 'restricted'

export type Visibility = 'private' | 'team' | 'organization' | 'restricted'

export type SupportedTool =
  | 'generic'
  | 'github_copilot'
  | 'codex'
  | 'cursor'
  | 'aws_kiro'
  | 'sourcegraph_cody'
  | 'claude_code'
  | 'jetbrains_ai'
  | 'internal_agent'

export const SUPPORTED_TOOLS: readonly SupportedTool[] = [
  'generic',
  'github_copilot',
  'codex',
  'cursor',
  'aws_kiro',
  'sourcegraph_cody',
  'claude_code',
  'jetbrains_ai',
  'internal_agent',
] as const

export const RISK_LEVELS: readonly RiskLevel[] = ['low', 'medium', 'high', 'restricted'] as const

export const VISIBILITIES: readonly Visibility[] = [
  'private',
  'team',
  'organization',
  'restricted',
] as const

export interface TagResponse {
  id: string
  type: string
  value: string
}

export interface VariantSummary {
  id: string
  tool: string
  instructions: string
  createdAt: string
  updatedAt: string
}

export interface SkillResponse {
  id: string
  name: string
  slug: string
  description: string
  ownerTeamId: string
  ownerTeamName: string | null
  lifecycleStatus: LifecycleStatus
  deprecationReason: string | null
  visibility: Visibility
  riskLevel: RiskLevel | null
  currentVersionNumber: number
  canonicalInstructions: string | null
  applicability: string | null
  limitations: string | null
  whenToUse: string | null
  examples: string | null
  changelog: string | null
  tags: TagResponse[]
  supportedTools: string[]
  variants: VariantSummary[]
  createdBy: string
  createdAt: string
  updatedAt: string
  publishedAt: string | null
}

export interface SkillSummaryResponse {
  id: string
  name: string
  slug: string
  description: string
  ownerTeamName: string | null
  lifecycleStatus: LifecycleStatus
  deprecationReason: string | null
  visibility: Visibility
  riskLevel: RiskLevel | null
  currentVersionNumber: number
  tags: TagResponse[]
  updatedAt: string
}

export interface SkillVersionResponse {
  id: string
  versionNumber: number
  canonicalInstructions: string | null
  applicability: string | null
  limitations: string | null
  whenToUse: string | null
  examples: string | null
  changelog: string | null
  variants: VariantSummary[]
  createdAt: string
  publishedAt: string | null
}

export interface CreateSkillRequest {
  name: string
  description: string
  ownerTeamId: string
  canonicalInstructions: string
}

export interface UpdateSkillRequest {
  name?: string
  description?: string
  canonicalInstructions?: string
  applicability?: string
  limitations?: string
  whenToUse?: string
  examples?: string
  changelog?: string
  tagIds?: string[]
  supportedTools?: string[]
  riskLevel?: RiskLevel | null
}

export interface ChangeVisibilityRequest {
  visibility: Visibility
}

export interface CreateVariantRequest {
  tool: SupportedTool
  instructions: string
}

export interface VariantResponse {
  id: string
  tool: string
  instructions: string
  createdAt: string
  updatedAt: string
}

/** Error body returned by the backend for a failed publish (HTTP 422). */
export interface PublishValidationResponse {
  error: string
  message: string
  missingFields: string[]
  timestamp: string
}

/** Filters accepted by the skill list endpoint. */
export interface SkillListParams {
  page?: number
  size?: number
  status?: string
  visibility?: string
  riskLevel?: string
  teamId?: string
  taskType?: string
  language?: string
  framework?: string
  tool?: string
  search?: string
  slug?: string
  includeArchived?: boolean
}

/**
 * Governance lifecycle actions, mirroring the backend
 * {@code com.alexandria.skill.lifecycle.LifecycleAction} enum.
 */
export type LifecycleAction = 'deprecate' | 'block' | 'archive' | 'restore'

/**
 * Frontend mirror of the backend {@code SkillLifecycleTransition} legality
 * table. Given a skill's current lifecycle status, returns the set of
 * governance actions permitted from that state. Kept in sync with the
 * backend policy so the UI only offers valid transitions; the server remains
 * the source of truth and rejects illegal transitions with HTTP 409.
 */
export function allowedLifecycleActions(
  status: LifecycleStatus,
): readonly LifecycleAction[] {
  switch (status) {
    // Active states (Draft, Published, Team_Approved, Company_Approved)
    case 'Draft':
    case 'Published':
    case 'Team_Approved':
    case 'Company_Approved':
      return ['deprecate', 'block', 'archive']
    case 'Deprecated':
      return ['restore', 'block', 'archive']
    case 'Archived':
      return ['restore', 'block']
    case 'Blocked':
      return ['restore']
    default:
      return []
  }
}
