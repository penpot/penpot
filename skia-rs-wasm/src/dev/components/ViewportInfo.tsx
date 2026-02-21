import { useWorkspaceStore } from '../../lib/renderer/store/workspace-store'

export function ViewportInfo() {
  const viewport = useWorkspaceStore((state) => state.viewport)
  useWorkspaceStore((state) => state.viewportVersion)
  const viewportInfo = viewport
    ? { zoom: viewport.zoom, panX: viewport.panX, panY: viewport.panY }
    : { zoom: 1, panX: 0, panY: 0 }

  return (
    <div
      className="viewport-info"
      style={{ marginTop: '10px', padding: '10px', backgroundColor: '#f5f5f5', borderRadius: '4px' }}
    >
      <h3>Viewport Controls</h3>
      <div style={{ marginBottom: '8px' }}>
        <strong>Zoom:</strong> {(viewportInfo.zoom * 100).toFixed(1)}% |
        <strong> Pan:</strong> X: {viewportInfo.panX.toFixed(1)}, Y: {viewportInfo.panY.toFixed(1)}
      </div>
      <div style={{ fontSize: '0.9em', color: '#666' }}>
        <p>
          <strong>Mouse:</strong> Wheel to zoom | Left mouse drag to pan/select | Shift+drag to pan
        </p>
        <p>
          <strong>Keyboard:</strong> Arrow keys to pan | +/- to zoom | 0 to reset
        </p>
      </div>
    </div>
  )
}
