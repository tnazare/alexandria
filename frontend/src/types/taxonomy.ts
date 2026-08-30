/**
 * TypeScript types mirroring the backend taxonomy DTOs
 * (com.alexandria.taxonomy.dto).
 */

export type TagType = 'task_type' | 'tool' | 'language' | 'framework'

export const TAG_TYPES: readonly TagType[] = [
  'task_type',
  'tool',
  'language',
  'framework',
] as const

export interface TaxonomyEntryResponse {
  id: string
  tagType: string
  value: string
  description: string | null
  active: boolean
  createdAt: string
}

export interface TaxonomyGroupResponse {
  tagType: string
  entries: TaxonomyEntryResponse[]
}

export interface CreateTaxonomyEntryRequest {
  value: string
  description?: string
}
