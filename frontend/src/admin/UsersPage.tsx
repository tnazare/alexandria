import { useState } from 'react'
import {
  useReactTable,
  getCoreRowModel,
  createColumnHelper,
  flexRender,
} from '@tanstack/react-table'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { listUsers, changeRole } from '@/api/users'
import { LoadingSpinner } from '@/shared/components/LoadingSpinner'
import { Pagination } from '@/shared/components/Pagination'
import type { UserResponse } from '@/shared/types'

const ROLES = [
  'Org_Admin',
  'Catalog_Admin',
  'Reviewer',
  'Team_Owner',
  'Skill_Author',
  'Skill_Consumer',
  'Read_Only_Auditor',
] as const

const PAGE_SIZE = 20

const columnHelper = createColumnHelper<UserResponse>()

function RoleDropdown({
  userId,
  currentRole,
}: {
  userId: string
  currentRole: string
}) {
  const queryClient = useQueryClient()

  const mutation = useMutation({
    mutationFn: (newRole: string) => changeRole(userId, newRole),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['users'] })
    },
  })

  return (
    <select
      value={currentRole}
      onChange={(e) => mutation.mutate(e.target.value)}
      disabled={mutation.isPending}
      className="rounded border border-neutral-300 bg-white px-2 py-1 text-sm focus:border-primary-500 focus:outline-none focus:ring-1 focus:ring-primary-500 disabled:opacity-50"
      aria-label={`Change role for user`}
    >
      {ROLES.map((role) => (
        <option key={role} value={role}>
          {role.replace(/_/g, ' ')}
        </option>
      ))}
    </select>
  )
}

function EnabledBadge({ enabled }: { enabled: boolean }) {
  return (
    <span
      className={`inline-flex items-center rounded-full px-2 py-0.5 text-xs font-medium ${
        enabled
          ? 'bg-green-100 text-green-800'
          : 'bg-red-100 text-red-800'
      }`}
    >
      {enabled ? 'Enabled' : 'Disabled'}
    </span>
  )
}

function formatDate(dateStr: string): string {
  return new Date(dateStr).toLocaleDateString(undefined, {
    year: 'numeric',
    month: 'short',
    day: 'numeric',
  })
}

const columns = [
  columnHelper.accessor('email', {
    header: 'Email',
    cell: (info) => (
      <span className="font-medium text-neutral-900">{info.getValue()}</span>
    ),
  }),
  columnHelper.accessor('displayName', {
    header: 'Display Name',
    cell: (info) => info.getValue() ?? '—',
  }),
  columnHelper.accessor('role', {
    header: 'Role',
    cell: (info) => (
      <RoleDropdown
        userId={info.row.original.id}
        currentRole={info.getValue()}
      />
    ),
  }),
  columnHelper.accessor('enabled', {
    header: 'Status',
    cell: (info) => <EnabledBadge enabled={info.getValue()} />,
  }),
  columnHelper.accessor('createdAt', {
    header: 'Created',
    cell: (info) => formatDate(info.getValue()),
  }),
]

export function UsersPage() {
  const [page, setPage] = useState(0)

  const { data, isLoading, isError, error } = useQuery({
    queryKey: ['users', page, PAGE_SIZE],
    queryFn: () => listUsers(page, PAGE_SIZE),
  })

  const table = useReactTable({
    data: data?.content ?? [],
    columns,
    getCoreRowModel: getCoreRowModel(),
  })

  if (isLoading) {
    return (
      <div className="flex items-center justify-center py-16">
        <LoadingSpinner size="lg" />
      </div>
    )
  }

  if (isError) {
    return (
      <div className="rounded-lg border border-red-200 bg-red-50 p-6 text-center">
        <p className="text-sm text-red-800">
          Failed to load users: {error instanceof Error ? error.message : 'Unknown error'}
        </p>
      </div>
    )
  }

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <h2 className="text-xl font-semibold text-neutral-900">Users</h2>
        {data && (
          <p className="text-sm text-neutral-500">
            {data.totalElements} user{data.totalElements !== 1 ? 's' : ''} total
          </p>
        )}
      </div>

      <div className="overflow-x-auto rounded-lg border border-neutral-200">
        <table className="w-full text-left text-sm">
          <thead className="border-b border-neutral-200 bg-neutral-50">
            {table.getHeaderGroups().map((headerGroup) => (
              <tr key={headerGroup.id}>
                {headerGroup.headers.map((header) => (
                  <th
                    key={header.id}
                    className="px-4 py-3 font-medium text-neutral-700"
                  >
                    {header.isPlaceholder
                      ? null
                      : flexRender(
                          header.column.columnDef.header,
                          header.getContext(),
                        )}
                  </th>
                ))}
              </tr>
            ))}
          </thead>
          <tbody className="divide-y divide-neutral-100">
            {table.getRowModel().rows.length === 0 ? (
              <tr>
                <td
                  colSpan={columns.length}
                  className="px-4 py-8 text-center text-neutral-500"
                >
                  No users found.
                </td>
              </tr>
            ) : (
              table.getRowModel().rows.map((row) => (
                <tr key={row.id} className="hover:bg-neutral-50">
                  {row.getVisibleCells().map((cell) => (
                    <td key={cell.id} className="px-4 py-3">
                      {flexRender(
                        cell.column.columnDef.cell,
                        cell.getContext(),
                      )}
                    </td>
                  ))}
                </tr>
              ))
            )}
          </tbody>
        </table>
      </div>

      {data && (
        <Pagination
          page={page}
          totalPages={data.totalPages}
          onPageChange={setPage}
        />
      )}
    </div>
  )
}
