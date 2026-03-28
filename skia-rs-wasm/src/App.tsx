import { useState, useCallback, useMemo, useEffect } from 'react'
import { Undo2, Redo2, FilePlus } from 'lucide-react'
import { CanvasWrapper } from './lib/renderer/canvas-wrapper'
import { ShapeToolbar } from './lib/components/ShapeToolbar'
import { LayersPanel } from './lib/components/layers-panel'
import { RightSidePanel } from './lib/components/RightSidePanel'
import { createNewDocument, setDocument, undo, redo } from './lib/page-crud'
import { Button } from '@/components/ui/button'

function App() {
  const [error, setError] = useState<string | null>(null)
  const rendererOptions = useMemo(
    () => ({ debug: false }),
    []
  )

  const handleError = useCallback((err: Error) => {
    setError(err.message)
    console.error('Error:', err)
  }, [])

  useEffect(() => {
    void setDocument(createNewDocument())
  }, [])

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      const t = e.target as HTMLElement | null
      if (t?.closest('input, textarea, select, [contenteditable="true"]')) return
      const mod = e.metaKey || e.ctrlKey
      if (mod && e.key === 'z' && !e.shiftKey) {
        e.preventDefault()
        void undo()
      } else if (mod && e.key === 'z' && e.shiftKey) {
        e.preventDefault()
        void redo()
      }
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [])

  return (
    /**
     * Root: fills the entire viewport. The canvas is an absolute fill layer.
     * All panels/toolbars are `fixed`, overlaying on top of the canvas.
     */
    <div
      className="canvas-container relative font-sans"
      style={{ width: '100vw', height: '100vh', overflow: 'hidden', background: 'var(--editor-canvas-chrome)' }}
    >
      {/* Canvas — fills the whole root */}
      <div style={{ position: 'absolute', inset: 0 }}>
        <CanvasWrapper
          rendererOptions={rendererOptions}
          onError={handleError}
          containerStyle={{ cursor: 'crosshair', width: '100%', height: '100%' }}
        />
      </div>

      {/* Fixed floating panels */}
      <LayersPanel />
      <RightSidePanel />
      <ShapeToolbar />

      {/* Document actions chip — absolute inside root, above canvas, below panels */}
      <div
        className="pointer-events-auto absolute top-3 z-10 flex gap-0.5 rounded-lg border border-border/80 bg-white p-1 shadow-md"
        style={{ right: 'calc(0.75rem + var(--properties-panel-width, 280px) + 0.75rem)' }}
        role="toolbar"
        aria-label="Document actions"
      >
        <Button
          type="button"
          variant="ghost"
          size="icon"
          className="h-9 w-9"
          aria-label="New document"
          title="New document"
          onClick={() => void setDocument(createNewDocument())}
        >
          <FilePlus className="size-4" />
        </Button>
        <Button
          type="button"
          variant="ghost"
          size="icon"
          className="h-9 w-9"
          aria-label="Undo"
          title="Undo"
          onClick={() => void undo()}
        >
          <Undo2 className="size-4" />
        </Button>
        <Button
          type="button"
          variant="ghost"
          size="icon"
          className="h-9 w-9"
          aria-label="Redo"
          title="Redo"
          onClick={() => void redo()}
        >
          <Redo2 className="size-4" />
        </Button>
      </div>

      {error && (
        <div
          className="pointer-events-auto fixed bottom-24 left-1/2 z-70 max-w-lg -translate-x-1/2 rounded-lg border border-destructive/40 bg-destructive/15 px-4 py-2 text-sm text-destructive shadow-lg backdrop-blur-sm"
          role="alert"
        >
          <span className="font-medium">Error:</span> {error}
          <button
            type="button"
            className="ml-3 rounded text-xs underline"
            onClick={() => setError(null)}
          >
            Dismiss
          </button>
        </div>
      )}
    </div>
  )
}

export default App
