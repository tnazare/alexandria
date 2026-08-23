import { type ReactNode, useEffect, useRef } from 'react'
import { Button } from './Button'

interface ModalProps {
  open: boolean
  onClose: () => void
  title: string
  children: ReactNode
  footer?: ReactNode
}

export function Modal({ open, onClose, title, children, footer }: ModalProps) {
  const dialogRef = useRef<HTMLDialogElement>(null)

  useEffect(() => {
    const dialog = dialogRef.current
    if (!dialog) return

    if (open) {
      dialog.showModal()
    } else {
      dialog.close()
    }
  }, [open])

  useEffect(() => {
    const dialog = dialogRef.current
    if (!dialog) return

    const handleClose = () => onClose()
    dialog.addEventListener('close', handleClose)
    return () => dialog.removeEventListener('close', handleClose)
  }, [onClose])

  if (!open) return null

  return (
    <dialog
      ref={dialogRef}
      className="fixed inset-0 z-50 m-auto w-full max-w-lg rounded-lg border-none bg-white p-0 shadow-xl backdrop:bg-black/50"
      aria-labelledby="modal-title"
    >
      <div className="flex items-center justify-between border-b border-neutral-200 px-6 py-4">
        <h2 id="modal-title" className="text-lg font-semibold text-neutral-900">
          {title}
        </h2>
        <Button variant="ghost" size="sm" onClick={onClose} aria-label="Close modal">
          <svg
            className="h-5 w-5"
            fill="none"
            viewBox="0 0 24 24"
            stroke="currentColor"
            aria-hidden="true"
          >
            <path
              strokeLinecap="round"
              strokeLinejoin="round"
              strokeWidth={2}
              d="M6 18L18 6M6 6l12 12"
            />
          </svg>
        </Button>
      </div>
      <div className="px-6 py-4">{children}</div>
      {footer && (
        <div className="flex justify-end gap-3 border-t border-neutral-200 px-6 py-4">
          {footer}
        </div>
      )}
    </dialog>
  )
}
