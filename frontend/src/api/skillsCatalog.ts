import {
  useMutation,
  useQuery,
  useQueryClient,
} from '@tanstack/react-query'
import { apiClient } from './client'
import type { PageResponse } from '@/shared/types'
import type {
  ChangeVisibilityRequest,
  CreateSkillRequest,
  CreateVariantRequest,
  SkillListParams,
  SkillResponse,
  SkillSummaryResponse,
  SkillVersionResponse,
  UpdateSkillRequest,
  VariantResponse,
} from '@/types/skill'

// --- Raw API functions ---

export async function createSkill(
  request: CreateSkillRequest,
): Promise<SkillResponse> {
  const response = await apiClient.post<SkillResponse>('/api/skills', request)
  return response.data
}

export async function listSkills(
  params: SkillListParams,
): Promise<PageResponse<SkillSummaryResponse>> {
  const response = await apiClient.get<PageResponse<SkillSummaryResponse>>(
    '/api/skills',
    { params },
  )
  return response.data
}

export async function getSkillById(skillId: string): Promise<SkillResponse> {
  const response = await apiClient.get<SkillResponse>(`/api/skills/${skillId}`)
  return response.data
}

export async function updateSkill(
  skillId: string,
  request: UpdateSkillRequest,
): Promise<SkillResponse> {
  const response = await apiClient.put<SkillResponse>(
    `/api/skills/${skillId}`,
    request,
  )
  return response.data
}

export async function deleteSkill(skillId: string): Promise<void> {
  await apiClient.delete(`/api/skills/${skillId}`)
}

export async function publishSkill(skillId: string): Promise<SkillResponse> {
  const response = await apiClient.post<SkillResponse>(
    `/api/skills/${skillId}/publish`,
  )
  return response.data
}

export async function changeVisibility(
  skillId: string,
  request: ChangeVisibilityRequest,
): Promise<SkillResponse> {
  const response = await apiClient.patch<SkillResponse>(
    `/api/skills/${skillId}/visibility`,
    request,
  )
  return response.data
}

// --- Lifecycle governance API functions (deprecate / block / archive / restore) ---

export async function deprecateSkill(
  skillId: string,
  reason?: string,
): Promise<SkillResponse> {
  const response = await apiClient.post<SkillResponse>(
    `/api/skills/${skillId}/deprecate`,
    reason ? { reason } : undefined,
  )
  return response.data
}

export async function blockSkill(skillId: string): Promise<SkillResponse> {
  const response = await apiClient.post<SkillResponse>(
    `/api/skills/${skillId}/block`,
  )
  return response.data
}

export async function archiveSkill(skillId: string): Promise<SkillResponse> {
  const response = await apiClient.post<SkillResponse>(
    `/api/skills/${skillId}/archive`,
  )
  return response.data
}

export async function restoreSkill(skillId: string): Promise<SkillResponse> {
  const response = await apiClient.post<SkillResponse>(
    `/api/skills/${skillId}/restore`,
  )
  return response.data
}

export async function listSkillVersions(
  skillId: string,
  page = 0,
  size = 20,
): Promise<PageResponse<SkillVersionResponse>> {
  const response = await apiClient.get<PageResponse<SkillVersionResponse>>(
    `/api/skills/${skillId}/versions`,
    { params: { page, size } },
  )
  return response.data
}

export async function getSkillVersion(
  skillId: string,
  versionNumber: number,
): Promise<SkillVersionResponse> {
  const response = await apiClient.get<SkillVersionResponse>(
    `/api/skills/${skillId}/versions/${versionNumber}`,
  )
  return response.data
}

// --- Variant API functions ---

export async function createVariant(
  skillId: string,
  versionNumber: number,
  request: CreateVariantRequest,
): Promise<VariantResponse> {
  const response = await apiClient.post<VariantResponse>(
    `/api/skills/${skillId}/versions/${versionNumber}/variants`,
    request,
  )
  return response.data
}

export async function updateVariant(
  skillId: string,
  versionNumber: number,
  tool: string,
  request: CreateVariantRequest,
): Promise<VariantResponse> {
  const response = await apiClient.put<VariantResponse>(
    `/api/skills/${skillId}/versions/${versionNumber}/variants/${tool}`,
    request,
  )
  return response.data
}

export async function deleteVariant(
  skillId: string,
  versionNumber: number,
  tool: string,
): Promise<void> {
  await apiClient.delete(
    `/api/skills/${skillId}/versions/${versionNumber}/variants/${tool}`,
  )
}

// --- Query keys ---

export const skillKeys = {
  all: ['skills-catalog'] as const,
  lists: () => [...skillKeys.all, 'list'] as const,
  list: (params: SkillListParams) => [...skillKeys.lists(), params] as const,
  details: () => [...skillKeys.all, 'detail'] as const,
  detail: (skillId: string) => [...skillKeys.details(), skillId] as const,
  versions: (skillId: string) =>
    [...skillKeys.detail(skillId), 'versions'] as const,
  version: (skillId: string, versionNumber: number) =>
    [...skillKeys.detail(skillId), 'version', versionNumber] as const,
}

// --- TanStack Query hooks ---

export function useSkills(params: SkillListParams) {
  return useQuery({
    queryKey: skillKeys.list(params),
    queryFn: () => listSkills(params),
  })
}

export function useSkill(skillId: string | undefined) {
  return useQuery({
    queryKey: skillKeys.detail(skillId ?? ''),
    queryFn: () => getSkillById(skillId as string),
    enabled: Boolean(skillId),
  })
}

export function useSkillVersions(
  skillId: string | undefined,
  page = 0,
  size = 20,
) {
  return useQuery({
    queryKey: [...skillKeys.versions(skillId ?? ''), page, size],
    queryFn: () => listSkillVersions(skillId as string, page, size),
    enabled: Boolean(skillId),
  })
}

export function useSkillVersion(
  skillId: string | undefined,
  versionNumber: number | undefined,
) {
  return useQuery({
    queryKey: skillKeys.version(skillId ?? '', versionNumber ?? -1),
    queryFn: () => getSkillVersion(skillId as string, versionNumber as number),
    enabled: Boolean(skillId) && versionNumber != null,
  })
}

export function useCreateSkill() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (request: CreateSkillRequest) => createSkill(request),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: skillKeys.lists() })
    },
  })
}

export function useUpdateSkill(skillId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (request: UpdateSkillRequest) => updateSkill(skillId, request),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: skillKeys.detail(skillId) })
      void queryClient.invalidateQueries({ queryKey: skillKeys.lists() })
    },
  })
}

export function useDeleteSkill() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (skillId: string) => deleteSkill(skillId),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: skillKeys.lists() })
    },
  })
}

export function usePublishSkill(skillId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: () => publishSkill(skillId),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: skillKeys.detail(skillId) })
      void queryClient.invalidateQueries({ queryKey: skillKeys.lists() })
    },
  })
}

export function useChangeVisibility(skillId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (request: ChangeVisibilityRequest) =>
      changeVisibility(skillId, request),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: skillKeys.detail(skillId) })
    },
  })
}

export function useDeprecateSkill(skillId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (reason?: string) => deprecateSkill(skillId, reason),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: skillKeys.detail(skillId) })
      void queryClient.invalidateQueries({ queryKey: skillKeys.lists() })
    },
  })
}

export function useBlockSkill(skillId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: () => blockSkill(skillId),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: skillKeys.detail(skillId) })
      void queryClient.invalidateQueries({ queryKey: skillKeys.lists() })
    },
  })
}

export function useArchiveSkill(skillId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: () => archiveSkill(skillId),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: skillKeys.detail(skillId) })
      void queryClient.invalidateQueries({ queryKey: skillKeys.lists() })
    },
  })
}

export function useRestoreSkill(skillId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: () => restoreSkill(skillId),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: skillKeys.detail(skillId) })
      void queryClient.invalidateQueries({ queryKey: skillKeys.lists() })
    },
  })
}

export function useCreateVariant(skillId: string, versionNumber: number) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (request: CreateVariantRequest) =>
      createVariant(skillId, versionNumber, request),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: skillKeys.detail(skillId) })
    },
  })
}

export function useUpdateVariant(skillId: string, versionNumber: number) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ tool, request }: { tool: string; request: CreateVariantRequest }) =>
      updateVariant(skillId, versionNumber, tool, request),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: skillKeys.detail(skillId) })
    },
  })
}

export function useDeleteVariant(skillId: string, versionNumber: number) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (tool: string) => deleteVariant(skillId, versionNumber, tool),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: skillKeys.detail(skillId) })
    },
  })
}
