import { useMemo } from 'react'
import { useSearchParams } from 'react-router'
import { useQuery } from '@tanstack/react-query'
import { listAuditEvents } from '@/api/audit'
import type { AuditEventResponse } from '@/shared/types'
import type { Column } from '@/shared/components/Table'
import { Table } from '@/shared/components/Table'
import { Pagination } from '@/shared/components/Pagination'
import { LoadingSpinner } from '@/shared/components/LoadingSpinner'

const PAGE_SIZE = 20

const ACTION_OPTIONS = [
  { value: '', label: 'All actions' },
  { value: 'user.auto_provisioned', label: 'User auto-provisioned' },
  { value: 'user.role_changed', label: 'User role changed' },
  { value: 'team.created', label: 'Team created' },
  { value: 'team_member.added', label: 'Team member added' },
  { value: 'team_member.removed', label: 'Team member removed' },
]

const TARGET_TYPE_OPTIONS = [
  { value: '', label: 'All target types' },
  { value: 'user', label: 'User' },
  { value: 'team', label: 'Team' },
  { value: 'team_member', label: 'Team member' },
]

function formatTimestamp(iso: string): string {
  const date = new Date(iso)
  return date.toLocaleString(undefined, {
    year: 'numeric',
    month: 'short',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
  })
}

export function AuditLogsPage() {
  const [searchParams, setSearchParams] = useSearchParams()

  const page = Number(searchParams.get('page') ?? '0')
  const action = searchParams.get('action') ?? ''
  const targetType = searchParams.get('targetType') ?? ''
  const actor = searchParams.get('actor') ?? ''
  const startDate = searchParams.get('startDate') ?? ''
  const endDate = searchParams.get('endDate') ?? ''

  const filters = useMemo(
    () => ({
      page,
      size: PAGE_SIZE,
      action: action || undefined,
      targetType: targetType || undefined,
      actorUserId: actor || undefined,
      startDate: startDate || undefined,
      endDate: endDate || undefined,
    }),
    [page, action, targetType, actor, startDate, endDate],
  )

  const { data, isLoading, isError } = useQuery({
    queryKey: ['audit-events', filters],
    queryFn: () => listAuditEvents(filters),
  })

  function updateParam(key: string, value: string) {
    setSearchParams((prev) => {
      const next = new URLSearchParams(prev)
      if (value) {
        next.set(key, value)
      } else {
        next.delete(key)
      }
      // Reset to first page when filters change
      if (key !== 'page') {
        next.delete('page')
      }
      return next
    })
  }

  function handlePageChange(newPage: number) {
    updateParam('page', newPage === 0 ? '' : String(newPage))
  }

  const columns: Column<AuditEventResponse>[] = [
    {
      key: 'timestamp',
      header: 'Timestamp',
      render: (row) => (
        <span className="whitespace-nowrap">{formatTimestamp(row.createdAt)}</span>
      ),
    },
    {
      key: 'actor',
      header: 'Actor',
      render: (row) => row.actorEmail,
    },
    {
      key: 'action',
      header: 'Action',
      render: (row) => (
        <span className="inline-flex rounded bg-neutral-100 px-2 py-0.5 text-xs font-medium text-neutral-700">
          {row.action}
        </span>
      ),
    },
    {
      key: 'targetType',
      header: 'Target Type',
      render: (row) => row.targetType,
    },
    {
      key: 'targetId',
      header: 'Target ID',
      render: (row) => (
        <span className="font-mono text-xs">{row.targetId}</span>
      ),
    },
  ]

  return (
    <div className="space-y-6">
      <h1 className="text-2xl font-semibold text-neutral-900">Audit Logs</h1>

      {/* Filter controls */}
      <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-5">
        <div>
          <label
            htmlFor="filter-action"
            className="mb-1 block text-sm font-medium text-neutral-700"
          >
            Action
          </label>
          <select
            id="filter-action"
            value={action}
            onChange={(e) => updateParam('action', e.target.value)}
            className="w-full rounded-md border border-neutral-300 px-3 py-2 text-sm focus:border-primary-500 focus:outline-none focus:ring-1 focus:ring-primary-500"
          >
            {ACTION_OPTIONS.map((opt) => (
              <option key={opt.value} value={opt.value}>
                {opt.label}
              </option>
            ))}
          </select>
        </div>

        <div>
          <label
            htmlFor="filter-target-type"
            className="mb-1 block text-sm font-medium text-neutral-700"
          >
            Target Type
          </label>
          <select
            id="filter-target-type"
            value={targetType}
            onChange={(e) => updateParam('targetType', e.target.value)}
            className="w-full rounded-md border border-neutral-300 px-3 py-2 text-sm focus:border-primary-500 focus:outline-none focus:ring-1 focus:ring-primary-500"
          >
            {TARGET_TYPE_OPTIONS.map((opt) => (
              <option key={opt.value} value={opt.value}>
                {opt.label}
              </option>
            ))}
          </select>
        </div>

        <div>
          <label
            htmlFor="filter-actor"
            className="mb-1 block text-sm font-medium text-neutral-700"
          >
            Actor
          </label>
          <input
            id="filter-actor"
            type="text"
            placeholder="Search by actor ID"
            value={actor}
            onChange={(e) => updateParam('actor', e.target.value)}
            className="w-full rounded-md border border-neutral-300 px-3 py-2 text-sm placeholder:text-neutral-400 focus:border-primary-500 focus:outline-none focus:ring-1 focus:ring-primary-500"
          />
        </div>

        <div>
          <label
            htmlFor="filter-start-date"
            className="mb-1 block text-sm font-medium text-neutral-700"
          >
            Start Date
          </label>
          <input
            id="filter-start-date"
            type="date"
            value={startDate}
            onChange={(e) => updateParam('startDate', e.target.value)}
            className="w-full rounded-md border border-neutral-300 px-3 py-2 text-sm focus:border-primary-500 focus:outline-none focus:ring-1 focus:ring-primary-500"
          />
        </div>

        <div>
          <label
            htmlFor="filter-end-date"
            className="mb-1 block text-sm font-medium text-neutral-700"
          >
            End Date
          </label>
          <input
            id="filter-end-date"
            type="date"
            value={endDate}
            onChange={(e) => updateParam('endDate', e.target.value)}
            className="w-full rounded-md border border-neutral-300 px-3 py-2 text-sm focus:border-primary-500 focus:outline-none focus:ring-1 focus:ring-primary-500"
          />
        </div>
      </div>

      {/* Content */}
      {isLoading && (
        <LoadingSpinner className="py-12" />
      )}

      {isError && (
        <div className="rounded-md border border-red-200 bg-red-50 p-4 text-sm text-red-700">
          Failed to load audit events. Please try again.
        </div>
      )}

      {data && (
        <>
          <Table
            columns={columns}
            data={data.content}
            keyExtractor={(row) => row.id}
            emptyMessage="No audit events match the current filters."
          />
          <Pagination
            page={data.page}
            totalPages={data.totalPages}
            onPageChange={handlePageChange}
          />
        </>
      )}
    </div>
  )
}
