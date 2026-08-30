import { useMemo, useState } from 'react'
import { AxiosError } from 'axios'
import {
  useCreateTaxonomyEntry,
  useDeactivateTaxonomyEntry,
  useTaxonomy,
  useUpdateTaxonomyEntry,
} from '@/api/taxonomy'
import { Button, LoadingSpinner, ToastContainer, useToast } from '@/shared/components'
import { TAG_TYPES } from '@/types/taxonomy'
import type { TagType, TaxonomyEntryResponse } from '@/types/taxonomy'

/**
 * Admin page for managing the controlled taxonomy. Route: /admin/taxonomy
 *
 * <p>Entries are grouped into tabs by tag type. Each tab supports creating new
 * entries, editing existing values, and deactivating entries (which preserves
 * existing skill associations but prevents new assignments).</p>
 */
export function TaxonomyManagementPage() {
  const { toasts, toast, dismissToast } = useToast()
  const [activeType, setActiveType] = useState<TagType>('task_type')

  const { data: groups, isLoading, isError } = useTaxonomy()
  const createMutation = useCreateTaxonomyEntry()
  const updateMutation = useUpdateTaxonomyEntry()
  const deactivateMutation = useDeactivateTaxonomyEntry()

  const [newValue, setNewValue] = useState('')
  const [newDescription, setNewDescription] = useState('')

  const entriesByType = useMemo(() => {
    const map = new Map<string, TaxonomyEntryResponse[]>()
    for (const group of groups ?? []) {
      map.set(group.tagType, group.entries)
    }
    return map
  }, [groups])

  const entries = entriesByType.get(activeType) ?? []

  function handleCreate(e: React.FormEvent) {
    e.preventDefault()
    if (!newValue.trim()) return
    createMutation.mutate(
      { tagType: activeType, request: { value: newValue.trim(), description: newDescription.trim() || undefined } },
      {
        onSuccess: () => {
          toast.success('Entry created')
          setNewValue('')
          setNewDescription('')
        },
        onError: (error: unknown) => {
          if (error instanceof AxiosError && error.response?.status === 409) {
            toast.error('An entry with this value already exists for this type')
          } else {
            toast.error('Failed to create entry')
          }
        },
      },
    )
  }

  function handleDeactivate(entry: TaxonomyEntryResponse) {
    if (!window.confirm(`Deactivate "${entry.value}"? It will remain on existing skills but cannot be added to new ones.`)) {
      return
    }
    deactivateMutation.mutate(
      { tagType: activeType, tagId: entry.id },
      {
        onSuccess: () => toast.success('Entry deactivated'),
        onError: () => toast.error('Failed to deactivate entry'),
      },
    )
  }

  if (isLoading) {
    return <LoadingSpinner className="py-12" />
  }
  if (isError) {
    return <div className="py-12 text-center text-neutral-600">Failed to load taxonomy.</div>
  }

  return (
    <div>
      <ToastContainer toasts={toasts} onDismiss={dismissToast} />

      <h1 className="mb-4 text-2xl font-semibold text-neutral-900">Taxonomy</h1>

      {/* Tabs */}
      <div className="mb-4 flex flex-wrap gap-1 border-b border-neutral-200">
        {TAG_TYPES.map((type) => (
          <button
            key={type}
            type="button"
            onClick={() => setActiveType(type)}
            aria-pressed={activeType === type}
            className={`-mb-px border-b-2 px-4 py-2 text-sm font-medium ${
              activeType === type
                ? 'border-primary-600 text-primary-700'
                : 'border-transparent text-neutral-500 hover:text-neutral-800'
            }`}
          >
            {type}
          </button>
        ))}
      </div>

      {/* Create form */}
      <form onSubmit={handleCreate} className="mb-6 flex flex-wrap items-end gap-2">
        <div className="flex-1 min-w-[12rem]">
          <label htmlFor="tax-value" className="mb-1 block text-xs font-medium text-neutral-600">
            Value
          </label>
          <input
            id="tax-value"
            type="text"
            value={newValue}
            onChange={(e) => setNewValue(e.target.value)}
            maxLength={100}
            className="w-full rounded-md border border-neutral-300 px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-primary-500"
            placeholder={`New ${activeType} value`}
          />
        </div>
        <div className="flex-1 min-w-[12rem]">
          <label htmlFor="tax-description" className="mb-1 block text-xs font-medium text-neutral-600">
            Description (optional)
          </label>
          <input
            id="tax-description"
            type="text"
            value={newDescription}
            onChange={(e) => setNewDescription(e.target.value)}
            maxLength={500}
            className="w-full rounded-md border border-neutral-300 px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-primary-500"
          />
        </div>
        <Button type="submit" loading={createMutation.isPending} disabled={!newValue.trim()}>
          Add
        </Button>
      </form>

      {/* Entries table */}
      {entries.length === 0 ? (
        <p className="text-sm text-neutral-500">No entries for {activeType} yet.</p>
      ) : (
        <table className="min-w-full text-sm">
          <thead>
            <tr className="border-b border-neutral-200 text-left text-neutral-500">
              <th className="pb-2 pr-4 font-medium">Value</th>
              <th className="pb-2 pr-4 font-medium">Description</th>
              <th className="pb-2 pr-4 font-medium">Status</th>
              <th className="pb-2 font-medium">Actions</th>
            </tr>
          </thead>
          <tbody>
            {entries.map((entry) => (
              <TaxonomyRow
                key={entry.id}
                entry={entry}
                tagType={activeType}
                onSaved={() => toast.success('Entry updated')}
                onSaveError={(msg) => toast.error(msg)}
                onDeactivate={() => handleDeactivate(entry)}
                updateMutation={updateMutation}
                deactivatePending={deactivateMutation.isPending}
              />
            ))}
          </tbody>
        </table>
      )}
    </div>
  )
}

interface TaxonomyRowProps {
  entry: TaxonomyEntryResponse
  tagType: TagType
  onSaved: () => void
  onSaveError: (message: string) => void
  onDeactivate: () => void
  updateMutation: ReturnType<typeof useUpdateTaxonomyEntry>
  deactivatePending: boolean
}

function TaxonomyRow({
  entry,
  tagType,
  onSaved,
  onSaveError,
  onDeactivate,
  updateMutation,
  deactivatePending,
}: TaxonomyRowProps) {
  const [editing, setEditing] = useState(false)
  const [value, setValue] = useState(entry.value)
  const [description, setDescription] = useState(entry.description ?? '')

  function save() {
    updateMutation.mutate(
      { tagType, tagId: entry.id, request: { value: value.trim(), description: description.trim() || undefined } },
      {
        onSuccess: () => {
          setEditing(false)
          onSaved()
        },
        onError: (error: unknown) => {
          if (error instanceof AxiosError && error.response?.status === 409) {
            onSaveError('That value already exists for this type')
          } else {
            onSaveError('Failed to update entry')
          }
        },
      },
    )
  }

  return (
    <tr className="border-b border-neutral-100" data-testid="taxonomy-row" data-active={entry.active}>
      <td className="py-2 pr-4">
        {editing ? (
          <input
            aria-label="Edit value"
            value={value}
            onChange={(e) => setValue(e.target.value)}
            className="w-full rounded border border-neutral-300 px-2 py-1"
          />
        ) : (
          <span className="font-medium text-neutral-900">{entry.value}</span>
        )}
      </td>
      <td className="py-2 pr-4 text-neutral-600">
        {editing ? (
          <input
            aria-label="Edit description"
            value={description}
            onChange={(e) => setDescription(e.target.value)}
            className="w-full rounded border border-neutral-300 px-2 py-1"
          />
        ) : (
          (entry.description ?? '—')
        )}
      </td>
      <td className="py-2 pr-4">
        <span
          className={`rounded-full px-2 py-0.5 text-xs font-medium ${
            entry.active ? 'bg-green-100 text-green-800' : 'bg-neutral-200 text-neutral-600'
          }`}
        >
          {entry.active ? 'Active' : 'Inactive'}
        </span>
      </td>
      <td className="py-2">
        <div className="flex gap-2">
          {editing ? (
            <>
              <Button size="sm" onClick={save} loading={updateMutation.isPending} disabled={!value.trim()}>
                Save
              </Button>
              <Button size="sm" variant="ghost" onClick={() => setEditing(false)}>
                Cancel
              </Button>
            </>
          ) : (
            <>
              <Button size="sm" variant="secondary" onClick={() => setEditing(true)}>
                Edit
              </Button>
              {entry.active && (
                <Button
                  size="sm"
                  variant="ghost"
                  onClick={onDeactivate}
                  loading={deactivatePending}
                >
                  Deactivate
                </Button>
              )}
            </>
          )}
        </div>
      </td>
    </tr>
  )
}
