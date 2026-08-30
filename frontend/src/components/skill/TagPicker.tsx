import { useMemo } from 'react'
import type { TaxonomyGroupResponse, TaxonomyEntryResponse } from '@/types/taxonomy'

interface TagPickerProps {
  /** Taxonomy groups from the API (may include inactive entries). */
  groups: TaxonomyGroupResponse[] | undefined
  /** Currently selected taxonomy entry IDs. */
  selectedIds: string[]
  onChange: (selectedIds: string[]) => void
  disabled?: boolean
}

/**
 * Flattens taxonomy groups to a lookup of active entries by ID.
 * Exported for testing.
 */
export function activeEntriesById(
  groups: TaxonomyGroupResponse[] | undefined,
): Map<string, TaxonomyEntryResponse> {
  const map = new Map<string, TaxonomyEntryResponse>()
  for (const group of groups ?? []) {
    for (const entry of group.entries) {
      if (entry.active) {
        map.set(entry.id, entry)
      }
    }
  }
  return map
}

/**
 * Multi-select tag picker driven by the controlled taxonomy. Only active
 * entries are selectable; selected tags render as removable chips grouped
 * visually by tag type.
 */
export function TagPicker({ groups, selectedIds, onChange, disabled = false }: TagPickerProps) {
  const activeById = useMemo(() => activeEntriesById(groups), [groups])

  const selectedEntries = selectedIds
    .map((id) => activeById.get(id))
    .filter((e): e is TaxonomyEntryResponse => e != null)

  function toggle(id: string) {
    if (selectedIds.includes(id)) {
      onChange(selectedIds.filter((x) => x !== id))
    } else {
      onChange([...selectedIds, id])
    }
  }

  function remove(id: string) {
    onChange(selectedIds.filter((x) => x !== id))
  }

  return (
    <div className="space-y-3" data-testid="tag-picker">
      {/* Selected chips */}
      {selectedEntries.length > 0 && (
        <div className="flex flex-wrap gap-2">
          {selectedEntries.map((entry) => (
            <span
              key={entry.id}
              className="inline-flex items-center gap-1 rounded-full bg-primary-100 px-2 py-0.5 text-xs font-medium text-primary-800"
              data-testid="tag-chip"
            >
              <span className="text-primary-500">{entry.tagType}:</span>
              {entry.value}
              {!disabled && (
                <button
                  type="button"
                  aria-label={`Remove ${entry.value}`}
                  onClick={() => remove(entry.id)}
                  className="ml-0.5 rounded-full text-primary-500 hover:text-primary-700"
                >
                  ×
                </button>
              )}
            </span>
          ))}
        </div>
      )}

      {/* Selectable options grouped by type (active only) */}
      <div className="space-y-3">
        {(groups ?? []).map((group) => {
          const activeEntries = group.entries.filter((e) => e.active)
          if (activeEntries.length === 0) {
            return null
          }
          return (
            <fieldset key={group.tagType} className="rounded-md border border-neutral-200 p-2">
              <legend className="px-1 text-xs font-semibold uppercase tracking-wide text-neutral-500">
                {group.tagType}
              </legend>
              <div className="flex flex-wrap gap-1.5">
                {activeEntries.map((entry) => {
                  const selected = selectedIds.includes(entry.id)
                  return (
                    <button
                      key={entry.id}
                      type="button"
                      disabled={disabled}
                      aria-pressed={selected}
                      onClick={() => toggle(entry.id)}
                      className={`rounded-full px-2 py-0.5 text-xs font-medium transition-colors ${
                        selected
                          ? 'bg-primary-600 text-white'
                          : 'bg-neutral-100 text-neutral-700 hover:bg-neutral-200'
                      } disabled:cursor-not-allowed disabled:opacity-50`}
                    >
                      {entry.value}
                    </button>
                  )
                })}
              </div>
            </fieldset>
          )
        })}
      </div>
    </div>
  )
}
