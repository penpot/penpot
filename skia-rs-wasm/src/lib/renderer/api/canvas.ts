/**
 * Canvas management and WebGL operations
 */

import type { WasmModule } from '../wasm-types'
import type { SelectionRectResult } from '../types'
import {
  hexToU32ARGB,
  uuidToU32Tuple,
  allocBytes,
  freeBytes,
  writeUUIDToHeap,
  offset8To32,
  getAllocSize,
} from '../utils'
import { translateBrowser } from './serializers'
import {
  checkContext,
  setContextInitialized,
  setContextLost,
  getContextInitialized,
  setCanvasPixels,
  canvasPixels,
} from './context'
import { UUID_U8_SIZE } from './constants'
import { requestRender } from './rendering'
import { getWebGLContext } from './webgl-helpers'

// Store canvas and context handle for cleanup
let storedCanvas: HTMLCanvasElement | null = null
let storedContextHandle: number | null = null
let storedWebGLContext: WebGL2RenderingContext | null = null
let storedOnContextLost: ((event: Event) => void) | null = null

/**
 * Set canvas background color
 */
export function setCanvasBackground(module: WasmModule, background: string): void {
  checkContext(module)
  const rgba = hexToU32ARGB(background, 1)
  module._set_canvas_background(rgba)
  requestRender(module, 'set-canvas-background')
}

/**
 * Set canvas size
 * Note: This is a simple DOM operation and doesn't require context to be initialized
 */
export function setCanvasSize(_module: WasmModule, canvas: HTMLCanvasElement, dpr: number = 1): void {
  const width = canvas.clientWidth || canvas.width
  const height = canvas.clientHeight || canvas.height
  canvas.width = width * dpr
  canvas.height = height * dpr
}

/**
 * Initialize canvas context
 */
export function initCanvasContext(
  module: WasmModule,
  canvas: HTMLCanvasElement,
  dpr: number = 1,
  debug: boolean = false
): boolean {
  // Check if context is already initialized
  if (getContextInitialized()) {
    console.warn('Canvas context already initialized, skipping initialization')
    return true
  }

  const gl = module.GL
  const flags = debug ? 1 : 0
  const contextId = 'webgl2'
  const contextAttributes = {
    alpha: true,
    antialias: false,
    depth: true,
    stencil: true,
    preserveDrawingBuffer: true,
  }

  const context = canvas.getContext(contextId, contextAttributes) as WebGL2RenderingContext | null
  if (!context) {
    return false
  }

  const handle = gl.registerContext(context, { majorVersion: 2 })
  gl.makeContextCurrent(handle)

  // Store context handle and context (matching ClojureScript order)
  storedContextHandle = handle
  storedWebGLContext = context

  // Force WEBGL_debug_renderer_info extension
  context.getExtension('WEBGL_debug_renderer_info')

  // Initialize WASM render engine
  const width = Math.floor(canvas.width / dpr)
  const height = Math.floor(canvas.height / dpr)
  module._init(width, height)
  module._set_render_options(flags, dpr)

  // Set browser and canvas size only after initialization (matching ClojureScript)
  const browser = translateBrowser(typeof navigator !== 'undefined' ? navigator.userAgent : undefined)
  module._set_browser(browser)
  setCanvasSize(module, canvas, dpr)

  // Store canvas and add event listener for WebGL context lost
  storedCanvas = canvas
  const onContextLost = (event: Event) => {
    event.preventDefault()
    setContextLost(true)
    console.warn('WebGL context lost')
  }
  canvas.addEventListener('webglcontextlost', onContextLost)
  storedOnContextLost = onContextLost

  // Mark context as initialized (last step, matching ClojureScript)
  setContextInitialized(true)
  return true
}

/**
 * Clear canvas
 */
export function clearCanvas(module: WasmModule, canvas?: HTMLCanvasElement): void {
  if (!getContextInitialized()) {
    return
  }

  try {
    setContextInitialized(false)
    module._clean_up()

    // Use provided canvas or stored canvas
    const canvasToClean = canvas || storedCanvas

    if (canvasToClean && storedOnContextLost) {
      canvasToClean.removeEventListener('webglcontextlost', storedOnContextLost)
    }

    // Clean up WebGL context
    const gl = module.GL as any
    if (gl && storedContextHandle !== null) {
      try {
        // Ask the browser to release resources explicitly if available
        if (storedWebGLContext) {
          const loseExt = storedWebGLContext.getExtension('WEBGL_lose_context')
          if (loseExt) {
            loseExt.loseContext()
          }
        }
        // deleteContext may not be in types but exists in Emscripten GL
        if (gl.deleteContext) {
          gl.deleteContext(storedContextHandle)
        }
      } finally {
        storedContextHandle = null
        storedWebGLContext = null
      }
    }

    // Clear stored references
    storedCanvas = null
    storedOnContextLost = null
  } catch (error) {
    console.error('Error during canvas cleanup:', error)
  }
}

/**
 * Draws ImageData to a WebGL2 context by creating a texture and rendering it
 */
function drawImageDataToWebGL(gl: WebGL2RenderingContext, imageData: ImageData): void {
  const width = imageData.width
  const height = imageData.height
  const texture = gl.createTexture()
  
  if (!texture) {
    throw new Error('Failed to create WebGL texture')
  }

  // Bind texture and set parameters
  gl.bindTexture(gl.TEXTURE_2D, texture)
  gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE)
  gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE)
  gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.LINEAR)
  gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.LINEAR)
  gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA, gl.RGBA, gl.UNSIGNED_BYTE, imageData)

  // Set up viewport
  gl.viewport(0, 0, width, height)

  // Vertex & Fragment shaders
  const vertexShaderSource = `#version 300 es
in vec2 a_position;
in vec2 a_texCoord;
out vec2 v_texCoord;
void main() {
  gl_Position = vec4(a_position, 0.0, 1.0);
  v_texCoord = a_texCoord;
}`

  const fragmentShaderSource = `#version 300 es
precision highp float;
in vec2 v_texCoord;
uniform sampler2D u_texture;
out vec4 fragColor;
void main() {
  fragColor = texture(u_texture, v_texCoord);
}`

  const vertexShader = gl.createShader(gl.VERTEX_SHADER)
  const fragmentShader = gl.createShader(gl.FRAGMENT_SHADER)
  const program = gl.createProgram()

  if (!vertexShader || !fragmentShader || !program) {
    throw new Error('Failed to create WebGL shaders or program')
  }

  gl.shaderSource(vertexShader, vertexShaderSource)
  gl.compileShader(vertexShader)
  if (!gl.getShaderParameter(vertexShader, gl.COMPILE_STATUS)) {
    const log = gl.getShaderInfoLog(vertexShader)
    console.error('Vertex shader compilation failed:', log)
    gl.deleteShader(vertexShader)
    gl.deleteShader(fragmentShader)
    gl.deleteProgram(program)
    gl.deleteTexture(texture)
    return
  }

  gl.shaderSource(fragmentShader, fragmentShaderSource)
  gl.compileShader(fragmentShader)
  if (!gl.getShaderParameter(fragmentShader, gl.COMPILE_STATUS)) {
    const log = gl.getShaderInfoLog(fragmentShader)
    console.error('Fragment shader compilation failed:', log)
    gl.deleteShader(vertexShader)
    gl.deleteShader(fragmentShader)
    gl.deleteProgram(program)
    gl.deleteTexture(texture)
    return
  }

  gl.attachShader(program, vertexShader)
  gl.attachShader(program, fragmentShader)
  gl.linkProgram(program)

  if (gl.getProgramParameter(program, gl.LINK_STATUS)) {
    gl.useProgram(program)

    // Create full-screen quad vertices (normalized device coordinates)
    const positionLocation = gl.getAttribLocation(program, 'a_position')
    const texcoordLocation = gl.getAttribLocation(program, 'a_texCoord')
    const positionBuffer = gl.createBuffer()
    const texcoordBuffer = gl.createBuffer()
    const positions = new Float32Array([-1.0, -1.0, 1.0, -1.0, -1.0, 1.0, -1.0, 1.0, 1.0, -1.0, 1.0, 1.0])
    const texcoords = new Float32Array([0.0, 0.0, 1.0, 0.0, 0.0, 1.0, 0.0, 1.0, 1.0, 0.0, 1.0, 1.0])

    // Set up position buffer
    gl.bindBuffer(gl.ARRAY_BUFFER, positionBuffer)
    gl.bufferData(gl.ARRAY_BUFFER, positions, gl.STATIC_DRAW)
    gl.enableVertexAttribArray(positionLocation)
    gl.vertexAttribPointer(positionLocation, 2, gl.FLOAT, false, 0, 0)

    // Set up texcoord buffer
    gl.bindBuffer(gl.ARRAY_BUFFER, texcoordBuffer)
    gl.bufferData(gl.ARRAY_BUFFER, texcoords, gl.STATIC_DRAW)
    gl.enableVertexAttribArray(texcoordLocation)
    gl.vertexAttribPointer(texcoordLocation, 2, gl.FLOAT, false, 0, 0)

    // Set texture uniform
    gl.activeTexture(gl.TEXTURE0)
    gl.bindTexture(gl.TEXTURE_2D, texture)
    const textureLocation = gl.getUniformLocation(program, 'u_texture')
    if (textureLocation) {
      gl.uniform1i(textureLocation, 0)
    }

    // Draw
    gl.drawArrays(gl.TRIANGLES, 0, 6)

    // Cleanup
    gl.deleteBuffer(positionBuffer)
    gl.deleteBuffer(texcoordBuffer)
    gl.deleteShader(vertexShader)
    gl.deleteShader(fragmentShader)
    gl.deleteProgram(program)
  } else {
    const log = gl.getProgramInfoLog(program)
    console.error('Program linking failed:', log)
  }

  gl.bindTexture(gl.TEXTURE_2D, null)
  gl.deleteTexture(texture)
}

/**
 * Captures the pixels of the viewport canvas
 */
export function captureCanvasPixels(module: WasmModule, canvas: HTMLCanvasElement): void {
  if (!canvas) {
    return
  }

  const gl = getWebGLContext(module)
  if (!gl) {
    return
  }

  const width = canvas.width
  const height = canvas.height
  const buffer = new Uint8ClampedArray(width * height * 4)
  gl.readPixels(0, 0, width, height, gl.RGBA, gl.UNSIGNED_BYTE, buffer)
  setCanvasPixels(new ImageData(buffer, width, height))
}

/**
 * Restores previous canvas pixels into the new canvas
 */
export function restorePreviousCanvasPixels(module: WasmModule, _canvas: HTMLCanvasElement): void {
  if (!canvasPixels) {
    return
  }

  const gl = getWebGLContext(module)
  if (!gl) {
    return
  }

  drawImageDataToWebGL(gl, canvasPixels)
  setCanvasPixels(null)
}

/**
 * Clears canvas pixels and buffers
 */
export function clearCanvasPixels(module: WasmModule, canvas: HTMLCanvasElement): void {

  const gl = getWebGLContext(module)
  if (gl) {
    gl.clearColor(0, 0, 0, 0.0)
    gl.clear(gl.COLOR_BUFFER_BIT)
    gl.clear(gl.DEPTH_BUFFER_BIT)
    gl.clear(gl.STENCIL_BUFFER_BIT)
  }

  canvas.style.filter = 'none'
  setCanvasPixels(null)
}

/**
 * Get selection rectangle
 */
export function getSelectionRect(module: WasmModule, entries: string[]): SelectionRectResult | null {
  checkContext(module)
  if (entries.length === 0) {
    return null
  }

  const size = getAllocSize(entries.length, UUID_U8_SIZE)
  const offset = offset8To32(allocBytes(module, size))
  const heapU32 = module.HEAPU32

  let currentOffset = offset
  for (const id of entries) {
    currentOffset = writeUUIDToHeap(currentOffset, heapU32, id)
  }

  const resultOffset = offset8To32(module._get_selection_rect())
  const heapF32 = module.HEAPF32

  // Read 10 float32 values: width, height, cx, cy, transform (a, b, c, d, e, f)
  const width = heapF32[resultOffset]
  const height = heapF32[resultOffset + 1]
  const cx = heapF32[resultOffset + 2]
  const cy = heapF32[resultOffset + 3]
  const transform = {
    a: heapF32[resultOffset + 4],
    b: heapF32[resultOffset + 5],
    c: heapF32[resultOffset + 6],
    d: heapF32[resultOffset + 7],
    e: heapF32[resultOffset + 8],
    f: heapF32[resultOffset + 9],
  }

  freeBytes(module)

  return {
    width,
    height,
    center: { x: cx, y: cy },
    transform,
  }
}

/**
 * Intersect position in shape
 */
export function intersectPositionInShape(module: WasmModule, id: string, position: { x: number; y: number }): boolean {
  checkContext(module)
  const [a, b, c, d] = uuidToU32Tuple(id)
  const result = module._intersect_position_in_shape(a, b, c, d, position.x, position.y)
  return result === 1
}

/**
 * Applies CSS blur filter to canvas
 */
export function applyCanvasBlur(_module: WasmModule, canvas: HTMLCanvasElement): void {
  if (canvas) {
    canvas.style.filter = 'blur(4px)'
  }
}

