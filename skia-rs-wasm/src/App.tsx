import { useState, useCallback, useMemo } from 'react'
import { CanvasWrapper } from './lib/renderer/canvas-wrapper'
import { ViewportInfo } from './dev/components/ViewportInfo'
import { SelectionInfo } from './dev/components/SelectionInfo'
import { DevToolbar } from './dev/DevToolbar'
import { ShapeToolbar } from './lib/components/ShapeToolbar'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'

function App() {
  const [status, setStatus] = useState<string>('Initializing...')
  const [error, setError] = useState<string | null>(null)
  const rendererOptions = useMemo(
    () => ({ dpr: window.devicePixelRatio || 1, debug: false }),
    []
  )

  const handleError = useCallback((err: Error) => {
    setError(err.message)
    setStatus('Error occurred')
    console.error('Error:', err)
  }, [])

  return (
    <div className="mx-auto max-w-6xl p-8 font-sans">
      <h1 className="mb-6 text-center text-3xl font-semibold text-foreground">WASM Renderer Example</h1>
      <Card className="mb-8">
        <CardHeader className="pb-2">
          <CardTitle className="text-sm font-medium text-muted-foreground">Renderer Status</CardTitle>
        </CardHeader>
        <CardContent className="space-y-3">
          <p className="text-sm font-medium text-foreground">Status: {status}</p>
        {error && (
          <div className="rounded-md border border-destructive/30 bg-destructive/10 p-3 text-destructive">
            <strong>Error:</strong>
            <pre className="mt-2 whitespace-pre-wrap wrap-break-word text-xs">{error}</pre>
          </div>
        )}
        </CardContent>
      </Card>
      <div className="canvas-container relative my-8 h-[600px] max-h-[80vh] w-full text-center [&>div]:h-full [&>div]:w-full [&_canvas]:max-h-full [&_canvas]:max-w-full">
        <ShapeToolbar />
        <CanvasWrapper
          rendererOptions={rendererOptions}
          onError={handleError}
          containerStyle={{ border: '1px solid #ccc', cursor: 'grab', width: '100%', height: '100%' }}
        />
      </div>
      <ViewportInfo />
      <SelectionInfo />
      <Card className="mt-8">
        <CardContent className="space-y-3 p-6 text-sm leading-relaxed text-muted-foreground">
        <p>
          This example demonstrates how to use the WASM renderer with viewport pan and zoom.
        </p>
        <p>
          The example renders a blue rectangle with rounded corners and a border.
        </p>
        <p>
          Use the controls above to pan and zoom the viewport. The viewport state is managed by the{' '}
          <code>Viewport</code> class and applied to the renderer using{' '}
          <code>renderer.applyViewport()</code>.
        </p>
        <p>
          The WASM module is loaded from <code>src/wasm/render-wasm.js</code>.
        </p>
        </CardContent>
      </Card>
      <DevToolbar />
    </div>
  )
}

export default App
