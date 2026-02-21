import { useState, useCallback, useMemo } from 'react'
import { CanvasWrapper } from './lib/renderer/canvas-wrapper'
import { ViewportInfo } from './dev/components/ViewportInfo'
import { SelectionInfo } from './dev/components/SelectionInfo'
import { DevToolbar } from './dev/DevToolbar'
import './App.css'

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
    <div className="app-container">
      <h1>WASM Renderer Example</h1>
      <div className="status-container">
        <p className="status">Status: {status}</p>
        {error && (
          <div className="error">
            <strong>Error:</strong>
            <pre>{error}</pre>
          </div>
        )}
      </div>
      <div className="canvas-container">
        <CanvasWrapper
          rendererOptions={rendererOptions}
          onError={handleError}
          containerStyle={{ border: '1px solid #ccc', cursor: 'grab' }}
        />
      </div>
      <ViewportInfo />
      <SelectionInfo />
      <div className="info">
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
      </div>
      <DevToolbar />
    </div>
  )
}

export default App
