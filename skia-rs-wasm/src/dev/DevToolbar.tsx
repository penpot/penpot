import { useState, useCallback, useMemo, useEffect } from 'react'
import type { PenpotNode, Shadow, Blur, Fill, Stroke, BlendMode, Matrix, Gradient, ConstraintH, ConstraintV, ImageColor, PartialImageColor, Selrect } from 'penpot-exporter'
import { createNode } from './node-factory'
import { getAllPresets, getPresetsByCategory, normalizePresetGradient, type Preset } from './presets'
import { isColorFill, isLinearGradient, isRadialGradient, isImageFill } from '../lib/renderer/api/constants'
import { useWorkspaceStore } from '../lib/renderer/store/workspace-store'
import { useWorkspaceDevStore } from '../lib/renderer/store/workspace-dev-store'
import { addNode, updateNode, deleteNode, setDocument, createNewDocument } from '../lib/renderer/store/page-crud'
import './DevToolbar.css'
import type { ShapeType } from '@skia-rs-wasm/common'

function isImageColor(img: ImageColor | PartialImageColor): img is ImageColor {
  return 'width' in img && 'height' in img
}

type Tab = 'add' | 'nodes' | 'advanced'

const CANVAS_WIDTH = 800
const CANVAS_HEIGHT = 600

export function DevToolbar() {
  const nodes = useWorkspaceDevStore((state) => state.currentPageNodes)
  const renderer = useWorkspaceStore((state) => state.renderer)
  const isPageReady = renderer !== null && renderer.isInitialized()
  const canvasWidth = CANVAS_WIDTH
  const canvasHeight = CANVAS_HEIGHT
  const [isExpanded, setIsExpanded] = useState(false)
  const [activeTab, setActiveTab] = useState<Tab>('add')

  // Add Node tab state
  const [selectedType, setSelectedType] = useState<ShapeType>('rect')
  const [x, setX] = useState(200)
  const [y, setY] = useState(200)
  const [width, setWidth] = useState(150)
  const [height, setHeight] = useState(100)
  const [fillColor, setFillColor] = useState('#3B82F6')
  const [fillOpacity, setFillOpacity] = useState(0.8)
  const [fillGradient, setFillGradient] = useState<Gradient | undefined>(undefined)
  const [strokeColor, setStrokeColor] = useState('#1E40AF')
  const [strokeWidth, setStrokeWidth] = useState(2)
  const [borderRadius, setBorderRadius] = useState(0)
  const [textContent, setTextContent] = useState('Hello World')
  const [radius, setRadius] = useState(50)
  const [shadows, setShadows] = useState<Shadow[]>([])
  const [blur, setBlur] = useState<Blur | undefined>(undefined)
  const [opacity, setOpacity] = useState(1)
  const [selectedCategory, setSelectedCategory] = useState<Preset['category']>('Complete')
  const [searchQuery, setSearchQuery] = useState('')

  // Advanced tab state
  const [selectedNodeId, setSelectedNodeId] = useState<string>('')
  const [isCreatingNew, setIsCreatingNew] = useState(true)

  // Advanced properties state
  const [advX, setAdvX] = useState(0)
  const [advY, setAdvY] = useState(0)
  const [advWidth, setAdvWidth] = useState(100)
  const [advHeight, setAdvHeight] = useState(100)
  const [advRotation, setAdvRotation] = useState(0)
  const [advTransform, setAdvTransform] = useState<Matrix>({ a: 1, b: 0, c: 0, d: 1, e: 0, f: 0 })
  const [advFills, setAdvFills] = useState<Fill[]>([])
  const [advStrokes, setAdvStrokes] = useState<Stroke[]>([])
  const [advShadows, setAdvShadows] = useState<Shadow[]>([])
  const [advBlur, setAdvBlur] = useState<Blur | undefined>(undefined)
  const [advOpacity, setAdvOpacity] = useState(1)
  const [advBlendMode, setAdvBlendMode] = useState<BlendMode>('normal')
  const [advHidden, setAdvHidden] = useState(false)
  const [advR1, setAdvR1] = useState(0)
  const [advR2, setAdvR2] = useState(0)
  const [advR3, setAdvR3] = useState(0)
  const [advR4, setAdvR4] = useState(0)
  const [advLinkCorners, setAdvLinkCorners] = useState(false)
  const [advConstraintsH, setAdvConstraintsH] = useState<ConstraintH | undefined>(undefined)
  const [advConstraintsV, setAdvConstraintsV] = useState<ConstraintV | undefined>(undefined)
  const [advClipContent, setAdvClipContent] = useState(false)
  const [advMaskedGroup, setAdvMaskedGroup] = useState(false)
  const [advGrowType, setAdvGrowType] = useState<'auto' | 'fixed' | 'fill' | undefined>(undefined)

  // Collapsible sections state
  const [expandedSections, setExpandedSections] = useState<Record<string, boolean>>({
    effects: true,
    fills: true,
    strokes: true,
    transform: true,
    layout: true,
    appearance: true,
    borderRadius: true,
  })

  const nonRootNodes = nodes.filter((n) => n.id !== '00000000-0000-0000-0000-000000000000')

  // Load node data when selection changes
  useEffect(() => {
    if (selectedNodeId && !isCreatingNew) {
      const node = nodes.find((n) => n.id === selectedNodeId)
      if (node) {
        setAdvX(node.x ?? 0)
        setAdvY(node.y ?? 0)
        setAdvWidth(node.width ?? 100)
        setAdvHeight(node.height ?? 100)
        setAdvRotation(node.rotation ?? 0)
        setAdvTransform(node.transform ?? { a: 1, b: 0, c: 0, d: 1, e: 0, f: 0 })
        setAdvFills(node.fills ?? [])
        setAdvStrokes(node.strokes ?? [])
        setAdvShadows(node.shadow ?? [])
        setAdvBlur(node.blur)
        setAdvOpacity(node.opacity ?? 1)
        setAdvBlendMode(node['blend-mode'] ?? 'normal')
        setAdvHidden(node.hidden ?? false)
        setAdvR1(node.r1 ?? 0)
        setAdvR2(node.r2 ?? 0)
        setAdvR3(node.r3 ?? 0)
        setAdvR4(node.r4 ?? 0)
        setAdvConstraintsH(node['constraints-h'])
        setAdvConstraintsV(node['constraints-v'])
        setAdvClipContent(node['clip-content'] ?? false)
        setAdvMaskedGroup(node.maskedGroup ?? false)
        setAdvGrowType(node['grow-type'])
      }
    } else if (isCreatingNew) {
      // Reset to defaults for new node
      setAdvX(200)
      setAdvY(200)
      setAdvWidth(150)
      setAdvHeight(100)
      setAdvRotation(0)
      setAdvTransform({ a: 1, b: 0, c: 0, d: 1, e: 0, f: 0 })
      setAdvFills([])
      setAdvStrokes([])
      setAdvShadows([])
      setAdvBlur(undefined)
      setAdvOpacity(1)
      setAdvBlendMode('normal')
      setAdvHidden(false)
      setAdvR1(0)
      setAdvR2(0)
      setAdvR3(0)
      setAdvR4(0)
      setAdvLinkCorners(false)
      setAdvConstraintsH(undefined)
      setAdvConstraintsV(undefined)
      setAdvClipContent(false)
      setAdvMaskedGroup(false)
      setAdvGrowType(undefined)
    }
  }, [selectedNodeId, isCreatingNew, nodes])

  const toggleSection = useCallback((section: string) => {
    setExpandedSections((prev) => ({ ...prev, [section]: !prev[section] }))
  }, [])

  const applyPreset = useCallback((preset: Preset) => {
    if (preset.x !== undefined) setX(preset.x)
    if (preset.y !== undefined) setY(preset.y)
    if (preset.width !== undefined) setWidth(preset.width)
    if (preset.height !== undefined) setHeight(preset.height)
    if (preset.radius !== undefined) setRadius(preset.radius)

    if (preset.fillGradient !== undefined) {
      setFillGradient(normalizePresetGradient(preset.fillGradient))
      setFillColor('#000000')
    } else if (preset.fillColor !== undefined) {
      setFillColor(preset.fillColor)
      setFillGradient(undefined)
    }

    if (preset.fillOpacity !== undefined) setFillOpacity(preset.fillOpacity)
    if (preset.strokeColor !== undefined) setStrokeColor(preset.strokeColor)
    if (preset.strokeWidth !== undefined) setStrokeWidth(preset.strokeWidth)
    if (preset.borderRadius !== undefined) setBorderRadius(preset.borderRadius)
    if (preset.textContent !== undefined) setTextContent(preset.textContent)
    if (preset.shadow !== undefined) setShadows(preset.shadow)
    if (preset.blur !== undefined) setBlur(preset.blur)
    if (preset.opacity !== undefined) setOpacity(preset.opacity)
  }, [])

  const handleAddNode = useCallback(() => {
    if (!isPageReady) return

    const options: any = {
      x,
      y,
      width,
      height,
      opacity,
    }

    if (selectedType === 'circle') {
      options.radius = radius
      options.width = radius * 2
      options.height = radius * 2
    }

    if (selectedType === 'rect' || selectedType === 'circle' || selectedType === 'path') {
      if (fillGradient) {
        options.fillColor = undefined
      } else {
        options.fillColor = fillColor
        options.fillOpacity = fillOpacity
      }
      options.strokeColor = strokeColor
      options.strokeWidth = strokeWidth
      if (selectedType === 'rect') {
        options.borderRadius = borderRadius
      }
    }

    if (selectedType === 'text') {
      options.text = textContent
      options.fillColor = fillColor
    }

    try {
      const newNode = createNode(selectedType, options)

      if (fillGradient && (selectedType === 'rect' || selectedType === 'circle' || selectedType === 'path')) {
        newNode.fills = [{ fillColorGradient: fillGradient }]
      }

      if (shadows.length > 0) {
        newNode.shadow = shadows
      }
      if (blur) {
        newNode.blur = blur
      }

      addNode(newNode)
    } catch (error) {
      console.error('Failed to create node:', error)
      alert(`Failed to create node: ${error instanceof Error ? error.message : 'Unknown error'}`)
    }
  }, [
    isPageReady,
    selectedType,
    x,
    y,
    width,
    height,
    fillColor,
    fillOpacity,
    fillGradient,
    strokeColor,
    strokeWidth,
    borderRadius,
    textContent,
    radius,
    shadows,
    blur,
    opacity,
    addNode,
  ])

  const handleRemoveNode = useCallback(
    (nodeId: string) => {
      if (nodeId === '00000000-0000-0000-0000-000000000000') {
        alert('Cannot remove root frame')
        return
      }
      deleteNode(nodeId)
    },
    [deleteNode]
  )

  const handleExport = useCallback(() => {
    const data = JSON.stringify(nodes, null, 2)
    const blob = new Blob([data], { type: 'application/json' })
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = 'nodes.json'
    a.click()
    URL.revokeObjectURL(url)
  }, [nodes])

  const handleImport = useCallback(() => {
    const input = document.createElement('input')
    input.type = 'file'
    input.accept = 'application/json'
    input.onchange = (e) => {
      const file = (e.target as HTMLInputElement).files?.[0]
      if (!file) return

      const reader = new FileReader()
      reader.onload = (event) => {
        try {
          JSON.parse(event.target?.result as string) as PenpotNode[] // validate JSON shape
          alert('Import functionality requires full scene replacement. Not implemented yet.')
        } catch (error) {
          alert('Failed to import nodes: Invalid JSON')
        }
      }
      reader.readAsText(file)
    }
    input.click()
  }, [nodes])

  // Advanced tab handlers
  const handleUpdateNode = useCallback(() => {
    if (!isPageReady) return

    if (isCreatingNew) {
      // Create new node
      const ROOT_UUID = '00000000-0000-0000-0000-000000000000'
      const newNode: PenpotNode = {
        id: crypto.randomUUID(),
        type: selectedType,
        x: advX,
        y: advY,
        width: advWidth,
        height: advHeight,
        parentId: ROOT_UUID,
        selrect: {
          x: advX,
          y: advY,
          x1: advX,
          y1: advY,
          x2: advX + advWidth,
          y2: advY + advHeight,
          width: advWidth,
          height: advHeight,
        },
        rotation: advRotation !== 0 ? advRotation : undefined,
        transform: advTransform.a !== 1 || advTransform.b !== 0 || advTransform.c !== 0 || advTransform.d !== 1 || advTransform.e !== 0 || advTransform.f !== 0 ? advTransform : undefined,
        fills: advFills.length > 0 ? advFills : undefined,
        strokes: advStrokes.length > 0 ? advStrokes : undefined,
        shadow: advShadows.length > 0 ? advShadows : undefined,
        blur: advBlur,
        opacity: advOpacity !== 1 ? advOpacity : undefined,
        blendMode: advBlendMode !== 'normal' ? advBlendMode : undefined,
        hidden: advHidden || undefined,
        r1: advR1 !== 0 ? advR1 : undefined,
        r2: advR2 !== 0 ? advR2 : undefined,
        r3: advR3 !== 0 ? advR3 : undefined,
        r4: advR4 !== 0 ? advR4 : undefined,
        constraintsH: advConstraintsH,
        constraintsV: advConstraintsV,
        showContent: advClipContent === false ? false : undefined,
        maskedGroup: advMaskedGroup || undefined,
        growType: advGrowType,
      }
      addNode(newNode)
    } else if (selectedNodeId) {
      // Update existing node
      const updates: Partial<PenpotNode> = {
        x: advX,
        y: advY,
        width: advWidth,
        height: advHeight,
        selrect: {
          x: advX,
          y: advY,
          x1: advX,
          y1: advY,
          x2: advX + advWidth,
          y2: advY + advHeight,
          width: advWidth,
          height: advHeight,
        },
        rotation: advRotation !== 0 ? advRotation : undefined,
        transform: advTransform.a !== 1 || advTransform.b !== 0 || advTransform.c !== 0 || advTransform.d !== 1 || advTransform.e !== 0 || advTransform.f !== 0 ? advTransform : undefined,
        fills: advFills.length > 0 ? advFills : undefined,
        strokes: advStrokes.length > 0 ? advStrokes : undefined,
        shadow: advShadows.length > 0 ? advShadows : undefined,
        blur: advBlur,
        opacity: advOpacity !== 1 ? advOpacity : undefined,
        blendMode: advBlendMode !== 'normal' ? advBlendMode : undefined,
        hidden: advHidden || undefined,
        r1: advR1 !== 0 ? advR1 : undefined,
        r2: advR2 !== 0 ? advR2 : undefined,
        r3: advR3 !== 0 ? advR3 : undefined,
        r4: advR4 !== 0 ? advR4 : undefined,
        constraintsH: advConstraintsH,
        constraintsV: advConstraintsV,
        showContent: advClipContent === false ? false : undefined,
        maskedGroup: advMaskedGroup || undefined,
        growType: advGrowType,
      }
      updateNode(selectedNodeId, updates)
    }
  }, [
    isPageReady,
    isCreatingNew,
    selectedNodeId,
    selectedType,
    advX,
    advY,
    advWidth,
    advHeight,
    advRotation,
    advTransform,
    advFills,
    advStrokes,
    advShadows,
    advBlur,
    advOpacity,
    advBlendMode,
    advHidden,
    advR1,
    advR2,
    advR3,
    advR4,
    advConstraintsH,
    advConstraintsV,
    advClipContent,
    advMaskedGroup,
    advGrowType,
    addNode,
    updateNode,
  ])

  // Fill management
  const addFill = useCallback(() => {
    const newFill: Fill = { fillColor: '#3B82F6', fillOpacity: 1 }
    setAdvFills((prev) => [...prev, newFill])
  }, [])

  const removeFill = useCallback((index: number) => {
    setAdvFills((prev) => prev.filter((_, i) => i !== index))
  }, [])

  const updateFill = useCallback((index: number, fill: Fill) => {
    setAdvFills((prev) => prev.map((f, i) => (i === index ? fill : f)))
  }, [])

  // Stroke management
  const addStroke = useCallback(() => {
    const newStroke: Stroke = {
      strokeColor: '#1E40AF',
      strokeOpacity: 1,
      strokeWidth: 2,
      strokeStyle: 'solid',
      strokeAlignment: 'center',
    }
    setAdvStrokes((prev) => [...prev, newStroke])
  }, [])

  const removeStroke = useCallback((index: number) => {
    setAdvStrokes((prev) => prev.filter((_, i) => i !== index))
  }, [])

  const updateStroke = useCallback((index: number, stroke: Stroke) => {
    setAdvStrokes((prev) => prev.map((s, i) => (i === index ? stroke : s)))
  }, [])

  // Shadow management
  const addShadow = useCallback(() => {
    const newShadow: Shadow = {
      id: null,
      color: { color: '#000000', opacity: 0.2 },
      blur: 8,
      spread: 0,
      offsetX: 2,
      offsetY: 2,
      style: 'drop-shadow',
      hidden: false,
    }
    setAdvShadows((prev) => [...prev, newShadow])
  }, [])

  const removeShadow = useCallback((index: number) => {
    setAdvShadows((prev) => prev.filter((_, i) => i !== index))
  }, [])

  const updateShadow = useCallback((index: number, shadow: Shadow) => {
    setAdvShadows((prev) => prev.map((s, i) => (i === index ? shadow : s)))
  }, [])

  // Border radius sync
  const handleBorderRadiusChange = useCallback((corner: 'r1' | 'r2' | 'r3' | 'r4', value: number) => {
    if (advLinkCorners) {
      setAdvR1(value)
      setAdvR2(value)
      setAdvR3(value)
      setAdvR4(value)
    } else {
      if (corner === 'r1') setAdvR1(value)
      if (corner === 'r2') setAdvR2(value)
      if (corner === 'r3') setAdvR3(value)
      if (corner === 'r4') setAdvR4(value)
    }
  }, [advLinkCorners])

  const nodeTypes: ShapeType[] = ['rect', 'circle', 'text', 'frame', 'group', 'path', 'bool', 'image', 'svg-raw']

  const presets = useMemo(() => {
    const allPresets = getAllPresets(canvasWidth, canvasHeight)
    let filtered = selectedCategory === 'Custom' ? [] : getPresetsByCategory(selectedCategory, canvasWidth, canvasHeight)

    if (searchQuery.trim()) {
      const query = searchQuery.toLowerCase()
      filtered = allPresets.filter(
        (p) =>
          p.name.toLowerCase().includes(query) ||
          (p.description && p.description.toLowerCase().includes(query))
      )
    }

    return filtered
  }, [selectedCategory, searchQuery, canvasWidth, canvasHeight])

  const categories: Preset['category'][] = ['Position', 'Size', 'Colors', 'Gradients', 'Effects', 'Complete', 'Custom']

  const blendModes: BlendMode[] = ['normal', 'darken', 'multiply', 'color-burn', 'lighten', 'screen', 'color-dodge', 'overlay', 'soft-light', 'hard-light', 'difference', 'exclusion', 'hue', 'saturation', 'color', 'luminosity']
  const constraintHOptions: (ConstraintH | '')[] = ['', 'left', 'right', 'leftright', 'center', 'scale']
  const constraintVOptions: (ConstraintV | '')[] = ['', 'top', 'bottom', 'topbottom', 'center', 'scale']

  return (
    <div className={`dev-toolbar ${isExpanded ? 'expanded' : ''}`}>
      <button className="dev-toolbar-toggle" onClick={() => setIsExpanded(!isExpanded)}>
        {isExpanded ? '✕' : '⚙️'}
      </button>

      {isExpanded && (
        <div className="dev-toolbar-panel">
          <h3>Dev Toolbar</h3>

          <div className="dev-toolbar-section">
            <button
              className="btn-primary"
              onClick={() => setDocument(createNewDocument())}
              disabled={renderer === null}
              title="Create a new document with one page and one node"
            >
              New Document
            </button>
          </div>

          {/* Tab Navigation */}
          <div className="dev-toolbar-tabs">
            <button
              className={`dev-toolbar-tab ${activeTab === 'add' ? 'active' : ''}`}
              onClick={() => setActiveTab('add')}
            >
              Add Node
            </button>
            <button
              className={`dev-toolbar-tab ${activeTab === 'nodes' ? 'active' : ''}`}
              onClick={() => setActiveTab('nodes')}
            >
              Nodes
            </button>
            <button
              className={`dev-toolbar-tab ${activeTab === 'advanced' ? 'active' : ''}`}
              onClick={() => setActiveTab('advanced')}
            >
              Advanced
            </button>
          </div>

          {/* Add Node Tab */}
          {activeTab === 'add' && (
            <div className="dev-toolbar-section">
              <h4>Add Node</h4>

              <div className="form-group">
                <label>Type:</label>
                <select value={selectedType} onChange={(e) => setSelectedType(e.target.value as ShapeType)}>
                  {nodeTypes.map((type) => (
                    <option key={type} value={type}>
                      {type}
                    </option>
                  ))}
                </select>
              </div>

              <div className="form-group">
                <label>Category:</label>
                <select value={selectedCategory} onChange={(e) => setSelectedCategory(e.target.value as Preset['category'])}>
                  {categories.map((cat) => (
                    <option key={cat} value={cat}>
                      {cat}
                    </option>
                  ))}
                </select>
              </div>

              {selectedCategory !== 'Custom' && (
                <>
                  <div className="form-group">
                    <label>Search Presets:</label>
                    <input
                      type="text"
                      placeholder="Search presets..."
                      value={searchQuery}
                      onChange={(e) => setSearchQuery(e.target.value)}
                      className="preset-search"
                    />
                  </div>

                  <div className="preset-list">
                    {presets.length === 0 ? (
                      <p className="empty-state">No presets found</p>
                    ) : (
                      presets.map((preset) => (
                        <div key={preset.name} className="preset-item" onClick={() => applyPreset(preset)}>
                          <div className="preset-header">
                            <span className="preset-name">{preset.name}</span>
                            {preset.width && preset.height && (
                              <span className="preset-size">{preset.width}×{preset.height}</span>
                            )}
                          </div>
                          {preset.description && (
                            <div className="preset-description">{preset.description}</div>
                          )}
                          <div className="preset-indicators">
                            {preset.fillColor && (
                              <span className="preset-indicator" style={{ backgroundColor: preset.fillColor }} title="Color" />
                            )}
                            {preset.fillGradient && (
                              <span className="preset-indicator gradient" title="Gradient" />
                            )}
                            {preset.shadow && preset.shadow.length > 0 && (
                              <span className="preset-indicator shadow" title="Shadow" />
                            )}
                            {preset.blur && (
                              <span className="preset-indicator blur" title="Blur" />
                            )}
                          </div>
                        </div>
                      ))
                    )}
                  </div>
                </>
              )}

              {selectedCategory === 'Custom' && (
                <>
                  <div className="form-group">
                    <label>X:</label>
                    <input
                      type="number"
                      value={x}
                      onChange={(e) => setX(Number(e.target.value))}
                      min={0}
                      max={canvasWidth}
                    />
                  </div>

                  <div className="form-group">
                    <label>Y:</label>
                    <input
                      type="number"
                      value={y}
                      onChange={(e) => setY(Number(e.target.value))}
                      min={0}
                      max={canvasHeight}
                    />
                  </div>

                  {selectedType !== 'circle' && (
                    <>
                      <div className="form-group">
                        <label>Width:</label>
                        <input
                          type="number"
                          value={width}
                          onChange={(e) => setWidth(Number(e.target.value))}
                          min={1}
                        />
                      </div>

                      <div className="form-group">
                        <label>Height:</label>
                        <input
                          type="number"
                          value={height}
                          onChange={(e) => setHeight(Number(e.target.value))}
                          min={1}
                        />
                      </div>
                    </>
                  )}

                  {selectedType === 'circle' && (
                    <div className="form-group">
                      <label>Radius:</label>
                      <input
                        type="number"
                        value={radius}
                        onChange={(e) => setRadius(Number(e.target.value))}
                        min={1}
                      />
                    </div>
                  )}

                  {(selectedType === 'rect' || selectedType === 'circle' || selectedType === 'path') && (
                    <>
                      <div className="form-group">
                        <label>Fill Color:</label>
                        <input
                          type="color"
                          value={fillColor}
                          onChange={(e) => {
                            setFillColor(e.target.value)
                            setFillGradient(undefined)
                          }}
                        />
                      </div>

                      <div className="form-group">
                        <label>Fill Opacity:</label>
                        <input
                          type="number"
                          value={fillOpacity}
                          onChange={(e) => setFillOpacity(Number(e.target.value))}
                          min={0}
                          max={1}
                          step={0.1}
                        />
                      </div>

                      <div className="form-group">
                        <label>Stroke Color:</label>
                        <input
                          type="color"
                          value={strokeColor}
                          onChange={(e) => setStrokeColor(e.target.value)}
                        />
                      </div>

                      <div className="form-group">
                        <label>Stroke Width:</label>
                        <input
                          type="number"
                          value={strokeWidth}
                          onChange={(e) => setStrokeWidth(Number(e.target.value))}
                          min={0}
                        />
                      </div>
                    </>
                  )}

                  {selectedType === 'rect' && (
                    <div className="form-group">
                      <label>Border Radius:</label>
                      <input
                        type="number"
                        value={borderRadius}
                        onChange={(e) => setBorderRadius(Number(e.target.value))}
                        min={0}
                      />
                    </div>
                  )}

                  {selectedType === 'text' && (
                    <>
                      <div className="form-group">
                        <label>Text:</label>
                        <input
                          type="text"
                          value={textContent}
                          onChange={(e) => setTextContent(e.target.value)}
                        />
                      </div>

                      <div className="form-group">
                        <label>Text Color:</label>
                        <input
                          type="color"
                          value={fillColor}
                          onChange={(e) => setFillColor(e.target.value)}
                        />
                      </div>
                    </>
                  )}
                </>
              )}

              <button className="btn-primary" onClick={handleAddNode} disabled={!isPageReady}>
                Add Node
              </button>
            </div>
          )}

          {/* Nodes Tab */}
          {activeTab === 'nodes' && (
            <>
              <div className="dev-toolbar-section">
                <h4>Nodes ({nonRootNodes.length})</h4>
                <div className="node-list">
                  {nonRootNodes.length === 0 ? (
                    <p className="empty-state">No nodes added yet</p>
                  ) : (
                    nonRootNodes.map((node) => (
                      <div key={node.id} className="node-item">
                        <span className="node-info">
                          {node.type} ({node.id.slice(0, 8)}...)
                        </span>
                        <button
                          className="btn-remove"
                          onClick={() => handleRemoveNode(node.id)}
                          title="Remove node"
                        >
                          ×
                        </button>
                      </div>
                    ))
                  )}
                </div>
              </div>

              <div className="dev-toolbar-section">
                <h4>Export/Import</h4>
                <div className="button-group">
                  <button className="btn-secondary" onClick={handleExport}>
                    Export JSON
                  </button>
                  <button className="btn-secondary" onClick={handleImport}>
                    Import JSON
                  </button>
                </div>
              </div>
            </>
          )}

          {/* Advanced Tab */}
          {activeTab === 'advanced' && (
            <div className="dev-toolbar-advanced">
              {/* Node Selection */}
              <div className="form-group">
                <label>Node:</label>
                <select
                  value={isCreatingNew ? '' : selectedNodeId}
                  onChange={(e) => {
                    if (e.target.value === '') {
                      setIsCreatingNew(true)
                      setSelectedNodeId('')
                    } else {
                      setIsCreatingNew(false)
                      setSelectedNodeId(e.target.value)
                    }
                  }}
                >
                  <option value="">Create New ({selectedType})</option>
                  {nonRootNodes.map((node) => (
                    <option key={node.id} value={node.id}>
                      {node.type} ({node.id.slice(0, 8)}...)
                    </option>
                  ))}
                </select>
              </div>

              {isCreatingNew && (
                <div className="form-group">
                  <label>Type:</label>
                  <select value={selectedType} onChange={(e) => setSelectedType(e.target.value as ShapeType)}>
                    {nodeTypes.map((type) => (
                      <option key={type} value={type}>
                        {type}
                      </option>
                    ))}
                  </select>
                </div>
              )}

              {/* Effects Section */}
              <div className="advanced-section">
                <div className="advanced-section-header" onClick={() => toggleSection('effects')}>
                  <h4>Effects</h4>
                  <span className="section-toggle">{expandedSections.effects ? '−' : '+'}</span>
                </div>
                {expandedSections.effects && (
                  <div className="advanced-section-content">
                    {/* Blur */}
                    <div className="form-group">
                      <label>
                        <input
                          type="checkbox"
                          checked={advBlur !== undefined}
                          onChange={(e) => {
                            if (e.target.checked) {
                              setAdvBlur({ type: 'layer-blur', value: 4, hidden: false })
                            } else {
                              setAdvBlur(undefined)
                            }
                          }}
                        />
                        Blur
                      </label>
                      {advBlur && (
                        <>
                          <select
                            value={advBlur.type}
                            onChange={(e) => setAdvBlur({ ...advBlur, type: e.target.value as 'layer-blur' })}
                          >
                            <option value="layer-blur">Layer</option>
                          </select>
                          <input
                            type="number"
                            value={advBlur.value}
                            onChange={(e) => setAdvBlur({ ...advBlur, value: Number(e.target.value) })}
                            min={0}
                            step={0.1}
                            placeholder="Value"
                          />
                          <label>
                            <input
                              type="checkbox"
                              checked={advBlur.hidden ?? false}
                              onChange={(e) => setAdvBlur({ ...advBlur, hidden: e.target.checked })}
                            />
                            Hidden
                          </label>
                        </>
                      )}
                    </div>

                    {/* Shadows */}
                    <div className="form-group">
                      <label>Shadows ({advShadows.length})</label>
                      <button className="btn-small" onClick={addShadow}>+ Add Shadow</button>
                      {advShadows.map((shadow, index) => (
                        <div key={index} className="array-item">
                          <div className="array-item-header">
                            <span>Shadow {index + 1}</span>
                            <button className="btn-remove-small" onClick={() => removeShadow(index)}>×</button>
                          </div>
                          <div className="form-group">
                            <label>Color:</label>
                            <input
                              type="color"
                              value={shadow.color.color}
                              onChange={(e) => updateShadow(index, { ...shadow, color: { ...shadow.color, color: e.target.value } })}
                            />
                            <input
                              type="number"
                              value={shadow.color.opacity ?? 1}
                              onChange={(e) => updateShadow(index, { ...shadow, color: { ...shadow.color, opacity: Number(e.target.value) } })}
                              min={0}
                              max={1}
                              step={0.1}
                              placeholder="Opacity"
                            />
                          </div>
                          <div className="form-group">
                            <label>Blur:</label>
                            <input
                              type="number"
                              value={shadow.blur}
                              onChange={(e) => updateShadow(index, { ...shadow, blur: Number(e.target.value) })}
                              min={0}
                            />
                          </div>
                          <div className="form-group">
                            <label>Spread:</label>
                            <input
                              type="number"
                              value={shadow.spread}
                              onChange={(e) => updateShadow(index, { ...shadow, spread: Number(e.target.value) })}
                            />
                          </div>
                          <div className="form-group">
                            <label>Offset X:</label>
                            <input
                              type="number"
                              value={shadow.offsetX}
                              onChange={(e) => updateShadow(index, { ...shadow, offsetX: Number(e.target.value) })}
                            />
                          </div>
                          <div className="form-group">
                            <label>Offset Y:</label>
                            <input
                              type="number"
                              value={shadow.offsetY}
                              onChange={(e) => updateShadow(index, { ...shadow, offsetY: Number(e.target.value) })}
                            />
                          </div>
                          <div className="form-group">
                            <label>Style:</label>
                            <select
                              value={shadow.style}
                              onChange={(e) => updateShadow(index, { ...shadow, style: e.target.value as 'drop-shadow' | 'inner-shadow' })}
                            >
                              <option value="drop-shadow">Drop</option>
                              <option value="inner-shadow">Inner</option>
                            </select>
                          </div>
                          <div className="form-group">
                            <label>
                              <input
                                type="checkbox"
                                checked={shadow.hidden ?? false}
                                onChange={(e) => updateShadow(index, { ...shadow, hidden: e.target.checked })}
                              />
                              Hidden
                            </label>
                          </div>
                        </div>
                      ))}
                    </div>
                  </div>
                )}
              </div>

              {/* Fills Section */}
              <div className="advanced-section">
                <div className="advanced-section-header" onClick={() => toggleSection('fills')}>
                  <h4>Fills</h4>
                  <span className="section-toggle">{expandedSections.fills ? '−' : '+'}</span>
                </div>
                {expandedSections.fills && (
                  <div className="advanced-section-content">
                    <div className="form-group">
                      <label>Fills ({advFills.length})</label>
                      <button className="btn-small" onClick={addFill}>+ Add Fill</button>
                      {advFills.map((fill, index) => {
                        const isColor = isColorFill(fill)
                        const isLinear = isLinearGradient(fill)
                        const isRadial = isRadialGradient(fill)
                        const isImage = isImageFill(fill)

                        return (
                          <div key={index} className="array-item">
                            <div className="array-item-header">
                              <span>Fill {index + 1}</span>
                              <button className="btn-remove-small" onClick={() => removeFill(index)}>×</button>
                            </div>
                            <div className="form-group">
                              <label>Type:</label>
                              <select
                                value={isColor ? 'color' : isLinear ? 'linear' : isRadial ? 'radial' : isImage ? 'image' : 'color'}
                                onChange={(e) => {
                                  const type = e.target.value
                                  if (type === 'color') {
                                    updateFill(index, { fillColor: '#3B82F6', fillOpacity: 1 })
                                  } else if (type === 'linear') {
                                    updateFill(index, {
                                      fillColorGradient: {
                                        type: 'linear',
                                        startX: 0,
                                        startY: 0,
                                        endX: 1,
                                        endY: 0,
                                        width: 0,
                                        stops: [
                                          { offset: 0, color: '#3B82F6', opacity: 1 },
                                          { offset: 1, color: '#1E40AF', opacity: 1 },
                                        ],
                                      },
                                    })
                                  } else if (type === 'radial') {
                                    updateFill(index, {
                                      fillColorGradient: {
                                        type: 'radial',
                                        startX: 0.5,
                                        startY: 0.5,
                                        endX: 0.5,
                                        endY: 0.5,
                                        width: 0.5,
                                        stops: [
                                          { offset: 0, color: '#3B82F6', opacity: 1 },
                                          { offset: 1, color: '#1E40AF', opacity: 1 },
                                        ],
                                      },
                                    })
                                  } else if (type === 'image') {
                                    updateFill(index, { fillImage: { id: '', width: 100, height: 100 } })
                                  }
                                }}
                              >
                                <option value="color">Solid Color</option>
                                <option value="linear">Linear Gradient</option>
                                <option value="radial">Radial Gradient</option>
                                <option value="image">Image</option>
                              </select>
                            </div>

                            {isColor && (
                              <>
                                <div className="form-group">
                                  <label>Color:</label>
                                  <input
                                    type="color"
                                    value={fill.fillColor ?? '#3B82F6'}
                                    onChange={(e) => updateFill(index, { ...fill, fillColor: e.target.value })}
                                  />
                                </div>
                                <div className="form-group">
                                  <label>Opacity:</label>
                                  <input
                                    type="number"
                                    value={fill.fillOpacity ?? 1}
                                    onChange={(e) => updateFill(index, { ...fill, fillOpacity: Number(e.target.value) })}
                                    min={0}
                                    max={1}
                                    step={0.1}
                                  />
                                </div>
                              </>
                            )}

                            {isLinear && fill.fillColorGradient && (
                              <>
                                <div className="form-group">
                                  <label>Start X:</label>
                                  <input
                                    type="number"
                                    value={fill.fillColorGradient.startX}
                                    onChange={(e) => updateFill(index, { ...fill, fillColorGradient: { ...fill.fillColorGradient!, startX: Number(e.target.value) } })}
                                    step={0.1}
                                  />
                                </div>
                                <div className="form-group">
                                  <label>Start Y:</label>
                                  <input
                                    type="number"
                                    value={fill.fillColorGradient.startY}
                                    onChange={(e) => updateFill(index, { ...fill, fillColorGradient: { ...fill.fillColorGradient!, startY: Number(e.target.value) } })}
                                    step={0.1}
                                  />
                                </div>
                                <div className="form-group">
                                  <label>End X:</label>
                                  <input
                                    type="number"
                                    value={fill.fillColorGradient.endX}
                                    onChange={(e) => updateFill(index, { ...fill, fillColorGradient: { ...fill.fillColorGradient!, endX: Number(e.target.value) } })}
                                    step={0.1}
                                  />
                                </div>
                                <div className="form-group">
                                  <label>End Y:</label>
                                  <input
                                    type="number"
                                    value={fill.fillColorGradient.endY}
                                    onChange={(e) => updateFill(index, { ...fill, fillColorGradient: { ...fill.fillColorGradient!, endY: Number(e.target.value) } })}
                                    step={0.1}
                                  />
                                </div>
                                <div className="form-group">
                                  <label>Stops ({fill.fillColorGradient.stops.length})</label>
                                  <button className="btn-small" onClick={() => {
                                    const newStop = { offset: 1, color: '#000000', opacity: 1 }
                                    updateFill(index, { ...fill, fillColorGradient: { ...fill.fillColorGradient!, stops: [...fill.fillColorGradient!.stops, newStop] } })
                                  }}>+ Add Stop</button>
                                  {fill.fillColorGradient.stops.map((stop: { offset: number; color: string; opacity?: number }, stopIndex: number) => (
                                    <div key={stopIndex} className="array-item nested">
                                      <div className="array-item-header">
                                        <span>Stop {stopIndex + 1}</span>
                                        <button className="btn-remove-small" onClick={() => {
                                          updateFill(index, { ...fill, fillColorGradient: { ...fill.fillColorGradient!, stops: fill.fillColorGradient!.stops.filter((_: unknown, i: number) => i !== stopIndex) } })
                                        }}>×</button>
                                      </div>
                                      <div className="form-group">
                                        <label>Offset:</label>
                                        <input
                                          type="number"
                                          value={stop.offset}
                                          onChange={(e) => {
                                            const newStops = [...fill.fillColorGradient!.stops]
                                            newStops[stopIndex] = { ...stop, offset: Number(e.target.value) }
                                            updateFill(index, { ...fill, fillColorGradient: { ...fill.fillColorGradient!, stops: newStops } })
                                          }}
                                          min={0}
                                          max={1}
                                          step={0.1}
                                        />
                                      </div>
                                      <div className="form-group">
                                        <label>Color:</label>
                                        <input
                                          type="color"
                                          value={typeof stop.color === 'string' ? stop.color : '#000000'}
                                          onChange={(e) => {
                                            const newStops = [...fill.fillColorGradient!.stops]
                                            newStops[stopIndex] = { ...stop, color: e.target.value }
                                            updateFill(index, { ...fill, fillColorGradient: { ...fill.fillColorGradient!, stops: newStops } })
                                          }}
                                        />
                                        <input
                                          type="number"
                                          value={stop.opacity ?? 1}
                                          onChange={(e) => {
                                            const newStops = [...fill.fillColorGradient!.stops]
                                            newStops[stopIndex] = { ...stop, opacity: Number(e.target.value) }
                                            updateFill(index, { ...fill, fillColorGradient: { ...fill.fillColorGradient!, stops: newStops } })
                                          }}
                                          min={0}
                                          max={1}
                                          step={0.1}
                                          placeholder="Opacity"
                                        />
                                      </div>
                                    </div>
                                  ))}
                                </div>
                              </>
                            )}

                            {isRadial && fill.fillColorGradient && (
                              <>
                                <div className="form-group">
                                  <label>CX:</label>
                                  <input
                                    type="number"
                                    value={fill.fillColorGradient.startX}
                                    onChange={(e) => updateFill(index, { ...fill, fillColorGradient: { ...fill.fillColorGradient!, startX: Number(e.target.value) } })}
                                    step={0.1}
                                  />
                                </div>
                                <div className="form-group">
                                  <label>CY:</label>
                                  <input
                                    type="number"
                                    value={fill.fillColorGradient.startY}
                                    onChange={(e) => updateFill(index, { ...fill, fillColorGradient: { ...fill.fillColorGradient!, startY: Number(e.target.value) } })}
                                    step={0.1}
                                  />
                                </div>
                                <div className="form-group">
                                  <label>Radius:</label>
                                  <input
                                    type="number"
                                    value={fill.fillColorGradient.width}
                                    onChange={(e) => updateFill(index, { ...fill, fillColorGradient: { ...fill.fillColorGradient!, width: Number(e.target.value) } })}
                                    step={0.1}
                                  />
                                </div>
                                <div className="form-group">
                                  <label>Stops ({fill.fillColorGradient.stops.length})</label>
                                  <button className="btn-small" onClick={() => {
                                    const newStop = { offset: 1, color: '#000000', opacity: 1 }
                                    updateFill(index, { ...fill, fillColorGradient: { ...fill.fillColorGradient!, stops: [...fill.fillColorGradient!.stops, newStop] } })
                                  }}>+ Add Stop</button>
                                  {fill.fillColorGradient.stops.map((stop: { offset: number; color: string; opacity?: number }, stopIndex: number) => (
                                    <div key={stopIndex} className="array-item nested">
                                      <div className="array-item-header">
                                        <span>Stop {stopIndex + 1}</span>
                                        <button className="btn-remove-small" onClick={() => {
                                          updateFill(index, { ...fill, fillColorGradient: { ...fill.fillColorGradient!, stops: fill.fillColorGradient!.stops.filter((_: unknown, i: number) => i !== stopIndex) } })
                                        }}>×</button>
                                      </div>
                                      <div className="form-group">
                                        <label>Offset:</label>
                                        <input
                                          type="number"
                                          value={stop.offset}
                                          onChange={(e) => {
                                            const newStops = [...fill.fillColorGradient!.stops]
                                            newStops[stopIndex] = { ...stop, offset: Number(e.target.value) }
                                            updateFill(index, { ...fill, fillColorGradient: { ...fill.fillColorGradient!, stops: newStops } })
                                          }}
                                          min={0}
                                          max={1}
                                          step={0.1}
                                        />
                                      </div>
                                      <div className="form-group">
                                        <label>Color:</label>
                                        <input
                                          type="color"
                                          value={typeof stop.color === 'string' ? stop.color : '#000000'}
                                          onChange={(e) => {
                                            const newStops = [...fill.fillColorGradient!.stops]
                                            newStops[stopIndex] = { ...stop, color: e.target.value }
                                            updateFill(index, { ...fill, fillColorGradient: { ...fill.fillColorGradient!, stops: newStops } })
                                          }}
                                        />
                                        <input
                                          type="number"
                                          value={stop.opacity ?? 1}
                                          onChange={(e) => {
                                            const newStops = [...fill.fillColorGradient!.stops]
                                            newStops[stopIndex] = { ...stop, opacity: Number(e.target.value) }
                                            updateFill(index, { ...fill, fillColorGradient: { ...fill.fillColorGradient!, stops: newStops } })
                                          }}
                                          min={0}
                                          max={1}
                                          step={0.1}
                                          placeholder="Opacity"
                                        />
                                      </div>
                                    </div>
                                  ))}
                                </div>
                              </>
                            )}

                            {isImage && fill.fillImage && isImageColor(fill.fillImage) && (() => {
                              const imageColor = fill.fillImage
                              return (
                                <>
                                  <div className="form-group">
                                    <label>Image ID:</label>
                                    <input
                                      type="text"
                                      value={imageColor.id ?? ''}
                                      onChange={(e) => updateFill(index, { ...fill, fillImage: { ...imageColor, id: e.target.value } })}
                                      placeholder="Image ID"
                                    />
                                  </div>
                                  <div className="form-group">
                                    <label>Width:</label>
                                    <input
                                      type="number"
                                      value={imageColor.width}
                                      onChange={(e) => updateFill(index, { ...fill, fillImage: { ...imageColor, width: Number(e.target.value) } })}
                                      min={1}
                                    />
                                  </div>
                                  <div className="form-group">
                                    <label>Height:</label>
                                    <input
                                      type="number"
                                      value={imageColor.height}
                                      onChange={(e) => updateFill(index, { ...fill, fillImage: { ...imageColor, height: Number(e.target.value) } })}
                                      min={1}
                                    />
                                  </div>
                                  <div className="form-group">
                                    <label>Opacity:</label>
                                    <input
                                      type="number"
                                      value={fill.fillOpacity ?? 1}
                                      onChange={(e) => updateFill(index, { ...fill, fillOpacity: Number(e.target.value) })}
                                      min={0}
                                      max={1}
                                      step={0.1}
                                    />
                                  </div>
                                </>
                              )
                            })()}
                          </div>
                        )
                      })}
                    </div>
                  </div>
                )}
              </div>

              {/* Strokes Section */}
              <div className="advanced-section">
                <div className="advanced-section-header" onClick={() => toggleSection('strokes')}>
                  <h4>Strokes</h4>
                  <span className="section-toggle">{expandedSections.strokes ? '−' : '+'}</span>
                </div>
                {expandedSections.strokes && (
                  <div className="advanced-section-content">
                    <div className="form-group">
                      <label>Strokes ({advStrokes.length})</label>
                      <button className="btn-small" onClick={addStroke}>+ Add Stroke</button>
                      {advStrokes.map((stroke, index) => {
                        const hasColor = stroke.strokeColor !== undefined
                        const hasGradient = stroke.strokeColorGradient !== undefined
                        const hasImage = stroke.strokeImage !== undefined

                        return (
                          <div key={index} className="array-item">
                            <div className="array-item-header">
                              <span>Stroke {index + 1}</span>
                              <button className="btn-remove-small" onClick={() => removeStroke(index)}>×</button>
                            </div>
                            <div className="form-group">
                              <label>Color Type:</label>
                              <select
                                value={hasColor ? 'color' : hasGradient ? 'gradient' : hasImage ? 'image' : 'color'}
                                onChange={(e) => {
                                  const type = e.target.value
                                  const newStroke: Stroke = { ...stroke }
                                  delete newStroke.strokeColor
                                  delete newStroke.strokeColorGradient
                                  delete newStroke.strokeImage

                                  if (type === 'color') {
                                    newStroke.strokeColor = '#1E40AF'
                                    newStroke.strokeOpacity = 1
                                  } else if (type === 'gradient') {
                                    newStroke.strokeColorGradient = {
                                      type: 'linear',
                                      startX: 0,
                                      startY: 0,
                                      endX: 1,
                                      endY: 0,
                                      width: 0,
                                      stops: [
                                        { offset: 0, color: '#1E40AF', opacity: 1 },
                                        { offset: 1, color: '#3B82F6', opacity: 1 },
                                      ],
                                    }
                                  } else if (type === 'image') {
                                    newStroke.strokeImage = { id: '', width: 100, height: 100 }
                                  }
                                  updateStroke(index, newStroke)
                                }}
                              >
                                <option value="color">Solid Color</option>
                                <option value="gradient">Gradient</option>
                                <option value="image">Image</option>
                              </select>
                            </div>

                            {hasColor && stroke.strokeColor && (
                              <>
                                <div className="form-group">
                                  <label>Color:</label>
                                  <input
                                    type="color"
                                    value={stroke.strokeColor}
                                    onChange={(e) => updateStroke(index, { ...stroke, strokeColor: e.target.value })}
                                  />
                                  <input
                                    type="number"
                                    value={stroke.strokeOpacity ?? 1}
                                    onChange={(e) => updateStroke(index, { ...stroke, strokeOpacity: Number(e.target.value) })}
                                    min={0}
                                    max={1}
                                    step={0.1}
                                    placeholder="Opacity"
                                  />
                                </div>
                              </>
                            )}

                            <div className="form-group">
                              <label>Width:</label>
                              <input
                                type="number"
                                value={stroke.strokeWidth}
                                onChange={(e) => updateStroke(index, { ...stroke, strokeWidth: Number(e.target.value) })}
                                min={0}
                              />
                            </div>
                            <div className="form-group">
                              <label>Style:</label>
                              <select
                                value={stroke.strokeStyle}
                                onChange={(e) => updateStroke(index, { ...stroke, strokeStyle: e.target.value as 'solid' | 'dashed' | 'dotted' })}>
                                <option value="solid">Solid</option>
                                <option value="dashed">Dashed</option>
                                <option value="dotted">Dotted</option>
                              </select>
                            </div>
                            <div className="form-group">
                              <label>Alignment:</label>
                              <select
                                value={stroke.strokeAlignment}
                                onChange={(e) => updateStroke(index, { ...stroke, strokeAlignment: e.target.value as 'center' | 'inner' | 'outer' })}>
                                <option value="center">Center</option>
                                <option value="inner">Inner</option>
                                <option value="outer">Outer</option>
                              </select>
                            </div>
                            <div className="form-group">
                              <label>Cap Start:</label>
                              <select
                                value={stroke.strokeCapStart ?? 'none'}
                                onChange={(e) => updateStroke(index, { ...stroke, strokeCapStart: e.target.value === 'none' ? undefined : (e.target.value as 'round' | 'square' | 'line-arrow' | 'triangle-arrow' | 'square-marker' | 'circle-marker' | 'diamond-marker') })}>
                                <option value="none">None</option>
                                <option value="round">Round</option>
                                <option value="square">Square</option>
                              </select>
                            </div>
                            <div className="form-group">
                              <label>Cap End:</label>
                              <select
                                value={stroke.strokeCapEnd ?? 'none'}
                                onChange={(e) => updateStroke(index, { ...stroke, strokeCapEnd: e.target.value === 'none' ? undefined : (e.target.value as 'round' | 'square' | 'line-arrow' | 'triangle-arrow' | 'square-marker' | 'circle-marker' | 'diamond-marker') })}>
                                <option value="none">None</option>
                                <option value="round">Round</option>
                                <option value="square">Square</option>
                              </select>
                            </div>
                            <div className="form-group">
                              <label>Opacity:</label>
                              <input
                                type="number"
                                value={stroke.strokeOpacity ?? 1}
                                onChange={(e) => updateStroke(index, { ...stroke, strokeOpacity: Number(e.target.value) })}
                                min={0}
                                max={1}
                                step={0.1}
                              />
                            </div>
                          </div>
                        )
                      })}
                    </div>
                  </div>
                )}
              </div>

              {/* Transform Section */}
              <div className="advanced-section">
                <div className="advanced-section-header" onClick={() => toggleSection('transform')}>
                  <h4>Transform</h4>
                  <span className="section-toggle">{expandedSections.transform ? '−' : '+'}</span>
                </div>
                {expandedSections.transform && (
                  <div className="advanced-section-content">
                    <div className="form-group">
                      <label>X:</label>
                      <input
                        type="number"
                        value={advX}
                        onChange={(e) => setAdvX(Number(e.target.value))}
                      />
                    </div>
                    <div className="form-group">
                      <label>Y:</label>
                      <input
                        type="number"
                        value={advY}
                        onChange={(e) => setAdvY(Number(e.target.value))}
                      />
                    </div>
                    <div className="form-group">
                      <label>Width:</label>
                      <input
                        type="number"
                        value={advWidth}
                        onChange={(e) => setAdvWidth(Number(e.target.value))}
                        min={1}
                      />
                    </div>
                    <div className="form-group">
                      <label>Height:</label>
                      <input
                        type="number"
                        value={advHeight}
                        onChange={(e) => setAdvHeight(Number(e.target.value))}
                        min={1}
                      />
                    </div>
                    <div className="form-group">
                      <label>Rotation (degrees):</label>
                      <input
                        type="number"
                        value={advRotation}
                        onChange={(e) => setAdvRotation(Number(e.target.value))}
                        step={0.1}
                      />
                    </div>
                    <div className="form-group">
                      <label>Transform Matrix (Advanced):</label>
                      <div className="transform-matrix">
                        <input
                          type="number"
                          value={advTransform.a}
                          onChange={(e) => setAdvTransform({ ...advTransform, a: Number(e.target.value) })}
                          step={0.01}
                          placeholder="a"
                        />
                        <input
                          type="number"
                          value={advTransform.b}
                          onChange={(e) => setAdvTransform({ ...advTransform, b: Number(e.target.value) })}
                          step={0.01}
                          placeholder="b"
                        />
                        <input
                          type="number"
                          value={advTransform.c}
                          onChange={(e) => setAdvTransform({ ...advTransform, c: Number(e.target.value) })}
                          step={0.01}
                          placeholder="c"
                        />
                        <input
                          type="number"
                          value={advTransform.d}
                          onChange={(e) => setAdvTransform({ ...advTransform, d: Number(e.target.value) })}
                          step={0.01}
                          placeholder="d"
                        />
                        <input
                          type="number"
                          value={advTransform.e}
                          onChange={(e) => setAdvTransform({ ...advTransform, e: Number(e.target.value) })}
                          step={0.01}
                          placeholder="e"
                        />
                        <input
                          type="number"
                          value={advTransform.f}
                          onChange={(e) => setAdvTransform({ ...advTransform, f: Number(e.target.value) })}
                          step={0.01}
                          placeholder="f"
                        />
                      </div>
                    </div>
                  </div>
                )}
              </div>

              {/* Layout Section */}
              <div className="advanced-section">
                <div className="advanced-section-header" onClick={() => toggleSection('layout')}>
                  <h4>Layout</h4>
                  <span className="section-toggle">{expandedSections.layout ? '−' : '+'}</span>
                </div>
                {expandedSections.layout && (
                  <div className="advanced-section-content">
                    <div className="form-group">
                      <label>Constraints Horizontal:</label>
                      <select
                        value={advConstraintsH ?? ''}
                        onChange={(e) => setAdvConstraintsH(e.target.value === '' ? undefined : e.target.value as ConstraintH)}
                      >
                        {constraintHOptions.map((opt) => (
                          <option key={opt || 'none'} value={opt}>{opt || 'None'}</option>
                        ))}
                      </select>
                    </div>
                    <div className="form-group">
                      <label>Constraints Vertical:</label>
                      <select
                        value={advConstraintsV ?? ''}
                        onChange={(e) => setAdvConstraintsV(e.target.value === '' ? undefined : e.target.value as ConstraintV)}
                      >
                        {constraintVOptions.map((opt) => (
                          <option key={opt || 'none'} value={opt}>{opt || 'None'}</option>
                        ))}
                      </select>
                    </div>
                    <div className="form-group">
                      <label>
                        <input
                          type="checkbox"
                          checked={advClipContent}
                          onChange={(e) => setAdvClipContent(e.target.checked)}
                        />
                        Clip Content
                      </label>
                    </div>
                    <div className="form-group">
                      <label>
                        <input
                          type="checkbox"
                          checked={advMaskedGroup}
                          onChange={(e) => setAdvMaskedGroup(e.target.checked)}
                        />
                        Masked Group
                      </label>
                    </div>
                    <div className="form-group">
                      <label>Grow Type:</label>
                      <select
                        value={advGrowType ?? ''}
                        onChange={(e) => setAdvGrowType(e.target.value === '' ? undefined : e.target.value as 'auto' | 'fixed' | 'fill')}
                      >
                        <option value="">None</option>
                        <option value="auto">Auto</option>
                        <option value="fixed">Fixed</option>
                        <option value="fill">Fill</option>
                      </select>
                    </div>
                  </div>
                )}
              </div>

              {/* Appearance Section */}
              <div className="advanced-section">
                <div className="advanced-section-header" onClick={() => toggleSection('appearance')}>
                  <h4>Appearance</h4>
                  <span className="section-toggle">{expandedSections.appearance ? '−' : '+'}</span>
                </div>
                {expandedSections.appearance && (
                  <div className="advanced-section-content">
                    <div className="form-group">
                      <label>Opacity:</label>
                      <input
                        type="number"
                        value={advOpacity}
                        onChange={(e) => setAdvOpacity(Number(e.target.value))}
                        min={0}
                        max={1}
                        step={0.1}
                      />
                    </div>
                    <div className="form-group">
                      <label>Blend Mode:</label>
                      <select
                        value={advBlendMode}
                        onChange={(e) => setAdvBlendMode(e.target.value as BlendMode)}
                      >
                        {blendModes.map((mode) => (
                          <option key={mode} value={mode}>{mode}</option>
                        ))}
                      </select>
                    </div>
                    <div className="form-group">
                      <label>
                        <input
                          type="checkbox"
                          checked={advHidden}
                          onChange={(e) => setAdvHidden(e.target.checked)}
                        />
                        Hidden
                      </label>
                    </div>
                  </div>
                )}
              </div>

              {/* Border Radius Section */}
              <div className="advanced-section">
                <div className="advanced-section-header" onClick={() => toggleSection('borderRadius')}>
                  <h4>Border Radius</h4>
                  <span className="section-toggle">{expandedSections.borderRadius ? '−' : '+'}</span>
                </div>
                {expandedSections.borderRadius && (
                  <div className="advanced-section-content">
                    <div className="form-group">
                      <label>
                        <input
                          type="checkbox"
                          checked={advLinkCorners}
                          onChange={(e) => setAdvLinkCorners(e.target.checked)}
                        />
                        Link Corners
                      </label>
                    </div>
                    <div className="form-group">
                      <label>Top Left (r1):</label>
                      <input
                        type="number"
                        value={advR1}
                        onChange={(e) => handleBorderRadiusChange('r1', Number(e.target.value))}
                        min={0}
                      />
                    </div>
                    <div className="form-group">
                      <label>Top Right (r2):</label>
                      <input
                        type="number"
                        value={advR2}
                        onChange={(e) => handleBorderRadiusChange('r2', Number(e.target.value))}
                        min={0}
                      />
                    </div>
                    <div className="form-group">
                      <label>Bottom Right (r3):</label>
                      <input
                        type="number"
                        value={advR3}
                        onChange={(e) => handleBorderRadiusChange('r3', Number(e.target.value))}
                        min={0}
                      />
                    </div>
                    <div className="form-group">
                      <label>Bottom Left (r4):</label>
                      <input
                        type="number"
                        value={advR4}
                        onChange={(e) => handleBorderRadiusChange('r4', Number(e.target.value))}
                        min={0}
                      />
                    </div>
                  </div>
                )}
              </div>

              <button className="btn-primary" onClick={handleUpdateNode} disabled={!isPageReady}>
                {isCreatingNew ? 'Create Node' : 'Update Node'}
              </button>
            </div>
          )}
        </div>
      )}
    </div>
  )
}
