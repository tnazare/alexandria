import {
  useMutation,
  useQuery,
  useQueryClient,
} from '@tanstack/react-query'
import { apiClient } from './client'
import type {
  CreateTaxonomyEntryRequest,
  TaxonomyEntryResponse,
  TaxonomyGroupResponse,
} from '@/types/taxonomy'

// --- Raw API functions ---

export async function getTaxonomyGrouped(): Promise<TaxonomyGroupResponse[]> {
  const response = await apiClient.get<TaxonomyGroupResponse[]>('/api/taxonomy')
  return response.data
}

export async function getTaxonomyByType(
  tagType: string,
): Promise<TaxonomyEntryResponse[]> {
  const response = await apiClient.get<TaxonomyEntryResponse[]>(
    `/api/taxonomy/${tagType}`,
  )
  return response.data
}

export async function createTaxonomyEntry(
  tagType: string,
  request: CreateTaxonomyEntryRequest,
): Promise<TaxonomyEntryResponse> {
  const response = await apiClient.post<TaxonomyEntryResponse>(
    `/api/taxonomy/${tagType}`,
    request,
  )
  return response.data
}

export async function updateTaxonomyEntry(
  tagType: string,
  tagId: string,
  request: CreateTaxonomyEntryRequest,
): Promise<TaxonomyEntryResponse> {
  const response = await apiClient.put<TaxonomyEntryResponse>(
    `/api/taxonomy/${tagType}/${tagId}`,
    request,
  )
  return response.data
}

export async function deactivateTaxonomyEntry(
  tagType: string,
  tagId: string,
): Promise<TaxonomyEntryResponse> {
  const response = await apiClient.patch<TaxonomyEntryResponse>(
    `/api/taxonomy/${tagType}/${tagId}/deactivate`,
  )
  return response.data
}

// --- Query keys ---

export const taxonomyKeys = {
  all: ['taxonomy'] as const,
  grouped: () => [...taxonomyKeys.all, 'grouped'] as const,
  byType: (tagType: string) => [...taxonomyKeys.all, 'type', tagType] as const,
}

// --- TanStack Query hooks ---

export function useTaxonomy() {
  return useQuery({
    queryKey: taxonomyKeys.grouped(),
    queryFn: getTaxonomyGrouped,
    // Taxonomy changes rarely; cache for 5 minutes so filter panels don't refetch
    // on every mount/navigation.
    staleTime: 5 * 60 * 1000,
  })
}

export function useTaxonomyByType(tagType: string | undefined) {
  return useQuery({
    queryKey: taxonomyKeys.byType(tagType ?? ''),
    queryFn: () => getTaxonomyByType(tagType as string),
    enabled: Boolean(tagType),
  })
}

export function useCreateTaxonomyEntry() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({
      tagType,
      request,
    }: {
      tagType: string
      request: CreateTaxonomyEntryRequest
    }) => createTaxonomyEntry(tagType, request),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: taxonomyKeys.all })
    },
  })
}

export function useUpdateTaxonomyEntry() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({
      tagType,
      tagId,
      request,
    }: {
      tagType: string
      tagId: string
      request: CreateTaxonomyEntryRequest
    }) => updateTaxonomyEntry(tagType, tagId, request),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: taxonomyKeys.all })
    },
  })
}

export function useDeactivateTaxonomyEntry() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ tagType, tagId }: { tagType: string; tagId: string }) =>
      deactivateTaxonomyEntry(tagType, tagId),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: taxonomyKeys.all })
    },
  })
}
