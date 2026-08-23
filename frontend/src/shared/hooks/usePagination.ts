import { useState, useCallback, useMemo } from 'react'

interface UsePaginationOptions {
  initialPage?: number
  initialSize?: number
}

interface UsePaginationResult {
  page: number
  size: number
  setPage: (page: number) => void
  setSize: (size: number) => void
  nextPage: () => void
  previousPage: () => void
  resetPage: () => void
  /** Query params object ready for API calls */
  params: { page: number; size: number }
}

/**
 * Manages pagination state for paginated API endpoints.
 * Pages are 0-indexed to match the backend's Spring Data convention.
 */
export function usePagination({
  initialPage = 0,
  initialSize = 20,
}: UsePaginationOptions = {}): UsePaginationResult {
  const [page, setPage] = useState(initialPage)
  const [size, setSize] = useState(initialSize)

  const nextPage = useCallback(() => setPage((p) => p + 1), [])
  const previousPage = useCallback(() => setPage((p) => Math.max(0, p - 1)), [])
  const resetPage = useCallback(() => setPage(0), [])

  const handleSetSize = useCallback((newSize: number) => {
    setSize(newSize)
    setPage(0) // Reset to first page when page size changes
  }, [])

  const params = useMemo(() => ({ page, size }), [page, size])

  return {
    page,
    size,
    setPage,
    setSize: handleSetSize,
    nextPage,
    previousPage,
    resetPage,
    params,
  }
}
