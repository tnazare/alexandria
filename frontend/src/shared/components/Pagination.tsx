import { Button } from './Button'

interface PaginationProps {
  page: number
  totalPages: number
  onPageChange: (page: number) => void
}

export function Pagination({ page, totalPages, onPageChange }: PaginationProps) {
  if (totalPages <= 1) return null

  const canGoPrevious = page > 0
  const canGoNext = page < totalPages - 1

  return (
    <nav
      className="flex items-center justify-between border-t border-neutral-200 px-1 py-3"
      aria-label="Pagination"
    >
      <p className="text-sm text-neutral-600">
        Page {page + 1} of {totalPages}
      </p>
      <div className="flex gap-2">
        <Button
          variant="secondary"
          size="sm"
          disabled={!canGoPrevious}
          onClick={() => onPageChange(page - 1)}
          aria-label="Previous page"
        >
          Previous
        </Button>
        <Button
          variant="secondary"
          size="sm"
          disabled={!canGoNext}
          onClick={() => onPageChange(page + 1)}
          aria-label="Next page"
        >
          Next
        </Button>
      </div>
    </nav>
  )
}
