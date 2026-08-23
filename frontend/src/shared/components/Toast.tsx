import { useEffect, useState, useCallback } from 'react'

type ToastVariant = 'success' | 'error' | 'warning' | 'info'

interface ToastMessage {
  id: string
  variant: ToastVariant
  message: string
  persistent?: boolean
}

const variantStyles: Record<ToastVariant, string> = {
  success: 'bg-green-50 border-green-300 text-green-800',
  error: 'bg-red-50 border-red-300 text-red-800',
  warning: 'bg-yellow-50 border-yellow-300 text-yellow-800',
  info: 'bg-blue-50 border-blue-300 text-blue-800',
}

const variantIcons: Record<ToastVariant, string> = {
  success: '✓',
  error: '✕',
  warning: '⚠',
  info: 'ℹ',
}

// --- Toast container component ---

interface ToastContainerProps {
  toasts: ToastMessage[]
  onDismiss: (id: string) => void
}

export function ToastContainer({ toasts, onDismiss }: ToastContainerProps) {
  return (
    <div
      className="pointer-events-none fixed inset-0 z-50 flex flex-col items-end gap-2 p-4"
      aria-live="polite"
      aria-label="Notifications"
    >
      {toasts.map((toast) => (
        <ToastItem key={toast.id} toast={toast} onDismiss={onDismiss} />
      ))}
    </div>
  )
}

// --- Individual toast item ---

function ToastItem({
  toast,
  onDismiss,
}: {
  toast: ToastMessage
  onDismiss: (id: string) => void
}) {
  useEffect(() => {
    if (toast.persistent) return

    const timer = setTimeout(() => onDismiss(toast.id), 5000)
    return () => clearTimeout(timer)
  }, [toast.id, toast.persistent, onDismiss])

  return (
    <div
      className={`pointer-events-auto flex w-full max-w-sm items-start gap-3 rounded-lg border p-4 shadow-md ${variantStyles[toast.variant]}`}
      role="alert"
    >
      <span className="text-lg" aria-hidden="true">
        {variantIcons[toast.variant]}
      </span>
      <p className="flex-1 text-sm">{toast.message}</p>
      <button
        onClick={() => onDismiss(toast.id)}
        className="text-current opacity-50 hover:opacity-100"
        aria-label="Dismiss notification"
      >
        <svg className="h-4 w-4" fill="none" viewBox="0 0 24 24" stroke="currentColor">
          <path
            strokeLinecap="round"
            strokeLinejoin="round"
            strokeWidth={2}
            d="M6 18L18 6M6 6l12 12"
          />
        </svg>
      </button>
    </div>
  )
}

// --- Toast hook for managing toasts ---

let idCounter = 0

export function useToast() {
  const [toasts, setToasts] = useState<ToastMessage[]>([])

  const addToast = useCallback(
    (variant: ToastVariant, message: string, persistent = false) => {
      const id = `toast-${++idCounter}`
      setToasts((prev) => [...prev, { id, variant, message, persistent }])
      return id
    },
    [],
  )

  const dismissToast = useCallback((id: string) => {
    setToasts((prev) => prev.filter((t) => t.id !== id))
  }, [])

  const toast = {
    success: (message: string) => addToast('success', message),
    error: (message: string) => addToast('error', message, true),
    warning: (message: string) => addToast('warning', message),
    info: (message: string) => addToast('info', message),
  }

  return { toasts, toast, dismissToast }
}

export type { ToastMessage, ToastVariant }
