/**
 * Comprehensive Preset Library for DevToolbar
 * Provides extensive presets for position, size, colors, gradients, shadows, blur, and complete combinations
 */

import type { Gradient, Shadow, Blur } from 'penpot-exporter'

/** Legacy gradient shape used in preset data (x1/y1/x2/y2 or cx/cy/r); normalized to Gradient when applied */
export type LegacyGradient =
  | { type: 'linear'; x1: number; y1: number; x2: number; y2: number; stops: Array<{ offset: number; color: string | { color: string; opacity?: number } }> }
  | { type: 'radial'; cx: number; cy: number; r: number; stops: Array<{ offset: number; color: string | { color: string; opacity?: number } }> }

export function normalizePresetGradient(g: Gradient | LegacyGradient | undefined): Gradient | undefined {
  if (!g) return undefined
  if ('startX' in g && g.startX !== undefined) return g as Gradient
  const stops = g.stops.map((s) => ({
    offset: s.offset,
    color: typeof s.color === 'string' ? s.color : s.color.color ?? '#000000',
    opacity: typeof s.color === 'object' && s.color && 'opacity' in s.color ? s.color.opacity : undefined,
  }))
  if (g.type === 'linear') {
    return { type: 'linear', startX: g.x1, startY: g.y1, endX: g.x2, endY: g.y2, width: 0, stops }
  }
  return {
    type: 'radial',
    startX: g.cx,
    startY: g.cy,
    endX: g.cx,
    endY: g.cy,
    width: g.r,
    stops,
  }
}

export interface Preset {
  name: string
  category: 'Position' | 'Size' | 'Colors' | 'Gradients' | 'Effects' | 'Complete' | 'Custom'
  x?: number
  y?: number
  width?: number
  height?: number
  radius?: number
  fillColor?: string
  fillOpacity?: number
  fillGradient?: Gradient | LegacyGradient
  strokeColor?: string
  strokeWidth?: number
  borderRadius?: number
  textContent?: string
  shadow?: Shadow[]
  blur?: Blur
  opacity?: number
  description?: string
}

/**
 * Calculate centered position based on canvas dimensions
 */
function centerX(canvasWidth: number, width: number): number {
  return Math.max(0, (canvasWidth - width) / 2)
}

function centerY(canvasHeight: number, height: number): number {
  return Math.max(0, (canvasHeight - height) / 2)
}

/**
 * Generate position presets based on canvas dimensions
 */
export function getPositionPresets(canvasWidth: number, canvasHeight: number, width: number = 200, height: number = 150): Preset[] {
  const cx = centerX(canvasWidth, width)
  const cy = centerY(canvasHeight, height)
  
  return [
    {
      name: 'Center',
      category: 'Position',
      x: cx,
      y: cy,
      description: 'Centered on canvas',
    },
    {
      name: 'Top Left',
      category: 'Position',
      x: 20,
      y: 20,
      description: 'Top-left corner',
    },
    {
      name: 'Top Right',
      category: 'Position',
      x: canvasWidth - width - 20,
      y: 20,
      description: 'Top-right corner',
    },
    {
      name: 'Bottom Left',
      category: 'Position',
      x: 20,
      y: canvasHeight - height - 20,
      description: 'Bottom-left corner',
    },
    {
      name: 'Bottom Right',
      category: 'Position',
      x: canvasWidth - width - 20,
      y: canvasHeight - height - 20,
      description: 'Bottom-right corner',
    },
    {
      name: 'Top Center',
      category: 'Position',
      x: cx,
      y: 20,
      description: 'Top center',
    },
    {
      name: 'Bottom Center',
      category: 'Position',
      x: cx,
      y: canvasHeight - height - 20,
      description: 'Bottom center',
    },
    {
      name: 'Left Center',
      category: 'Position',
      x: 20,
      y: cy,
      description: 'Left center',
    },
    {
      name: 'Right Center',
      category: 'Position',
      x: canvasWidth - width - 20,
      y: cy,
      description: 'Right center',
    },
  ]
}

/**
 * Size presets from device sizes and standard dimensions
 */
export const sizePresets: Preset[] = [
  // Device sizes - Apple
  { name: 'iPhone 16', category: 'Size', width: 393, height: 852 },
  { name: 'iPhone 16 Pro', category: 'Size', width: 402, height: 874 },
  { name: 'iPhone 16 Pro Max', category: 'Size', width: 440, height: 956 },
  { name: 'iPhone 16 Plus', category: 'Size', width: 430, height: 932 },
  { name: 'iPhone 15/15 Pro', category: 'Size', width: 393, height: 852 },
  { name: 'iPhone 13/14', category: 'Size', width: 390, height: 844 },
  { name: 'iPhone SE', category: 'Size', width: 320, height: 568 },
  { name: 'iPad', category: 'Size', width: 768, height: 1024 },
  { name: 'iPad Pro 11in', category: 'Size', width: 834, height: 1194 },
  { name: 'iPad Pro 12.9in', category: 'Size', width: 1027, height: 1366 },
  { name: 'MacBook Air', category: 'Size', width: 1280, height: 832 },
  { name: 'MacBook Pro 14in', category: 'Size', width: 1512, height: 982 },
  { name: 'MacBook Pro 16in', category: 'Size', width: 1728, height: 1117 },
  
  // Android
  { name: 'Google Pixel 7 Pro', category: 'Size', width: 412, height: 892 },
  { name: 'Samsung Galaxy S22', category: 'Size', width: 360, height: 780 },
  
  // Web
  { name: 'Web 1280', category: 'Size', width: 1280, height: 800 },
  { name: 'Web 1366', category: 'Size', width: 1366, height: 768 },
  { name: 'Web 1920', category: 'Size', width: 1920, height: 1080 },
  
  // Print
  { name: 'A4', category: 'Size', width: 794, height: 1123 },
  { name: 'A3', category: 'Size', width: 1123, height: 1587 },
  { name: 'Letter', category: 'Size', width: 816, height: 1054 },
  
  // Social Media
  { name: 'Instagram Post', category: 'Size', width: 1080, height: 1350 },
  { name: 'Instagram Story', category: 'Size', width: 1080, height: 1920 },
  { name: 'Facebook Post', category: 'Size', width: 1200, height: 630 },
  { name: 'LinkedIn Post', category: 'Size', width: 520, height: 320 },
  { name: 'X Post', category: 'Size', width: 1024, height: 512 },
  
  // Common UI sizes
  { name: 'Card Small', category: 'Size', width: 200, height: 150 },
  { name: 'Card Medium', category: 'Size', width: 300, height: 200 },
  { name: 'Card Large', category: 'Size', width: 400, height: 300 },
  { name: 'Button', category: 'Size', width: 120, height: 40 },
  { name: 'Button Large', category: 'Size', width: 200, height: 50 },
]

/**
 * Color presets
 */
export const colorPresets: Preset[] = [
  // Material Design
  { name: 'Blue Primary', category: 'Colors', fillColor: '#2196F3', fillOpacity: 1 },
  { name: 'Blue Dark', category: 'Colors', fillColor: '#1976D2', fillOpacity: 1 },
  { name: 'Blue Light', category: 'Colors', fillColor: '#64B5F6', fillOpacity: 1 },
  { name: 'Green Primary', category: 'Colors', fillColor: '#4CAF50', fillOpacity: 1 },
  { name: 'Red Primary', category: 'Colors', fillColor: '#F44336', fillOpacity: 1 },
  { name: 'Orange Primary', category: 'Colors', fillColor: '#FF9800', fillOpacity: 1 },
  { name: 'Purple Primary', category: 'Colors', fillColor: '#9C27B0', fillOpacity: 1 },
  { name: 'Pink Primary', category: 'Colors', fillColor: '#E91E63', fillOpacity: 1 },
  { name: 'Teal Primary', category: 'Colors', fillColor: '#009688', fillOpacity: 1 },
  { name: 'Indigo Primary', category: 'Colors', fillColor: '#3F51B5', fillOpacity: 1 },
  
  // UI Colors
  { name: 'Success', category: 'Colors', fillColor: '#4CAF50', fillOpacity: 1 },
  { name: 'Error', category: 'Colors', fillColor: '#F44336', fillOpacity: 1 },
  { name: 'Warning', category: 'Colors', fillColor: '#FF9800', fillOpacity: 1 },
  { name: 'Info', category: 'Colors', fillColor: '#2196F3', fillOpacity: 1 },
  
  // Grayscale
  { name: 'Black', category: 'Colors', fillColor: '#000000', fillOpacity: 1 },
  { name: 'Dark Gray', category: 'Colors', fillColor: '#424242', fillOpacity: 1 },
  { name: 'Gray', category: 'Colors', fillColor: '#9E9E9E', fillOpacity: 1 },
  { name: 'Light Gray', category: 'Colors', fillColor: '#E0E0E0', fillOpacity: 1 },
  { name: 'White', category: 'Colors', fillColor: '#FFFFFF', fillOpacity: 1 },
  
  // Brand Colors
  { name: 'Twitter Blue', category: 'Colors', fillColor: '#1DA1F2', fillOpacity: 1 },
  { name: 'Facebook Blue', category: 'Colors', fillColor: '#1877F2', fillOpacity: 1 },
  { name: 'Instagram Purple', category: 'Colors', fillColor: '#E4405F', fillOpacity: 1 },
  { name: 'LinkedIn Blue', category: 'Colors', fillColor: '#0077B5', fillOpacity: 1 },
  { name: 'YouTube Red', category: 'Colors', fillColor: '#FF0000', fillOpacity: 1 },
]

/**
 * Gradient presets
 */
export const gradientPresets: Preset[] = [
  // Linear Gradients
  {
    name: 'Sunrise',
    category: 'Gradients',
    fillGradient: {
      type: 'linear',
      x1: 0,
      y1: 0,
      x2: 0,
      y2: 1,
      stops: [
        { offset: 0, color: { color: '#FF6B6B', opacity: 1 } },
        { offset: 1, color: { color: '#FFE66D', opacity: 1 } },
      ],
    },
  },
  {
    name: 'Sunset',
    category: 'Gradients',
    fillGradient: {
      type: 'linear',
      x1: 0,
      y1: 0,
      x2: 0,
      y2: 1,
      stops: [
        { offset: 0, color: { color: '#FF6B9D', opacity: 1 } },
        { offset: 1, color: { color: '#C44569', opacity: 1 } },
      ],
    },
  },
  {
    name: 'Ocean',
    category: 'Gradients',
    fillGradient: {
      type: 'linear',
      x1: 0,
      y1: 0,
      x2: 0,
      y2: 1,
      stops: [
        { offset: 0, color: { color: '#4ECDC4', opacity: 1 } },
        { offset: 1, color: { color: '#44A08D', opacity: 1 } },
      ],
    },
  },
  {
    name: 'Forest',
    category: 'Gradients',
    fillGradient: {
      type: 'linear',
      x1: 0,
      y1: 0,
      x2: 0,
      y2: 1,
      stops: [
        { offset: 0, color: { color: '#134E5E', opacity: 1 } },
        { offset: 1, color: { color: '#71B280', opacity: 1 } },
      ],
    },
  },
  {
    name: 'Fire',
    category: 'Gradients',
    fillGradient: {
      type: 'linear',
      x1: 0,
      y1: 0,
      x2: 0,
      y2: 1,
      stops: [
        { offset: 0, color: { color: '#FF416C', opacity: 1 } },
        { offset: 1, color: { color: '#FF4B2B', opacity: 1 } },
      ],
    },
  },
  {
    name: 'Ice',
    category: 'Gradients',
    fillGradient: {
      type: 'linear',
      x1: 0,
      y1: 0,
      x2: 0,
      y2: 1,
      stops: [
        { offset: 0, color: { color: '#74B9FF', opacity: 1 } },
        { offset: 1, color: { color: '#0984E3', opacity: 1 } },
      ],
    },
  },
  {
    name: 'Purple Dream',
    category: 'Gradients',
    fillGradient: {
      type: 'linear',
      x1: 0,
      y1: 0,
      x2: 0,
      y2: 1,
      stops: [
        { offset: 0, color: { color: '#667EEA', opacity: 1 } },
        { offset: 1, color: { color: '#764BA2', opacity: 1 } },
      ],
    },
  },
  {
    name: 'Button Gradient',
    category: 'Gradients',
    fillGradient: {
      type: 'linear',
      x1: 0,
      y1: 0,
      x2: 1,
      y2: 0,
      stops: [
        { offset: 0, color: { color: '#667EEA', opacity: 1 } },
        { offset: 1, color: { color: '#764BA2', opacity: 1 } },
      ],
    },
  },
  
  // Radial Gradients
  {
    name: 'Spotlight',
    category: 'Gradients',
    fillGradient: {
      type: 'radial',
      cx: 0.5,
      cy: 0.5,
      r: 0.5,
      stops: [
        { offset: 0, color: { color: '#FFFFFF', opacity: 1 } },
        { offset: 1, color: { color: '#000000', opacity: 1 } },
      ],
    },
  },
  {
    name: 'Glow',
    category: 'Gradients',
    fillGradient: {
      type: 'radial',
      cx: 0.5,
      cy: 0.5,
      r: 0.5,
      stops: [
        { offset: 0, color: { color: '#FFD700', opacity: 1 } },
        { offset: 1, color: { color: '#FF8C00', opacity: 0.3 } },
      ],
    },
  },
  {
    name: 'Bubble',
    category: 'Gradients',
    fillGradient: {
      type: 'radial',
      cx: 0.3,
      cy: 0.3,
      r: 0.7,
      stops: [
        { offset: 0, color: { color: '#FFFFFF', opacity: 0.8 } },
        { offset: 1, color: { color: '#4ECDC4', opacity: 1 } },
      ],
    },
  },
]

/**
 * Shadow effect presets
 */
export const shadowPresets: Preset[] = [
  // Drop Shadows
  {
    name: 'Soft Shadow',
    category: 'Effects',
    shadow: [
      {
        color: { color: '#000000', opacity: 0.2 },
        blur: 8,
        spread: 0,
        'offset-x': 2,
        'offset-y': 2,
        style: 'drop',
      },
    ],
  },
  {
    name: 'Medium Shadow',
    category: 'Effects',
    shadow: [
      {
        color: { color: '#000000', opacity: 0.25 },
        blur: 12,
        spread: 0,
        'offset-x': 4,
        'offset-y': 4,
        style: 'drop',
      },
    ],
  },
  {
    name: 'Hard Shadow',
    category: 'Effects',
    shadow: [
      {
        color: { color: '#000000', opacity: 0.4 },
        blur: 4,
        spread: 0,
        'offset-x': 2,
        'offset-y': 2,
        style: 'drop',
      },
    ],
  },
  {
    name: 'Large Shadow',
    category: 'Effects',
    shadow: [
      {
        color: { color: '#000000', opacity: 0.15 },
        blur: 20,
        spread: 0,
        'offset-x': 0,
        'offset-y': 8,
        style: 'drop',
      },
    ],
  },
  {
    name: 'Blue Shadow',
    category: 'Effects',
    shadow: [
      {
        color: { color: '#2196F3', opacity: 0.3 },
        blur: 12,
        spread: 0,
        'offset-x': 4,
        'offset-y': 4,
        style: 'drop',
      },
    ],
  },
  {
    name: 'Purple Shadow',
    category: 'Effects',
    shadow: [
      {
        color: { color: '#9C27B0', opacity: 0.3 },
        blur: 12,
        spread: 0,
        'offset-x': 4,
        'offset-y': 4,
        style: 'drop',
      },
    ],
  },
  {
    name: 'Red Shadow',
    category: 'Effects',
    shadow: [
      {
        color: { color: '#F44336', opacity: 0.3 },
        blur: 12,
        spread: 0,
        'offset-x': 4,
        'offset-y': 4,
        style: 'drop',
      },
    ],
  },
  // Material Design Elevation
  {
    name: 'Elevation 1',
    category: 'Effects',
    shadow: [
      {
        color: { color: '#000000', opacity: 0.2 },
        blur: 1,
        spread: 0,
        'offset-x': 0,
        'offset-y': 1,
        style: 'drop',
      },
    ],
  },
  {
    name: 'Elevation 2',
    category: 'Effects',
    shadow: [
      {
        color: { color: '#000000', opacity: 0.14 },
        blur: 2,
        spread: 0,
        'offset-x': 0,
        'offset-y': 2,
        style: 'drop',
      },
      {
        color: { color: '#000000', opacity: 0.12 },
        blur: 1,
        spread: 0,
        'offset-x': 0,
        'offset-y': 1,
        style: 'drop',
      },
    ],
  },
  {
    name: 'Elevation 4',
    category: 'Effects',
    shadow: [
      {
        color: { color: '#000000', opacity: 0.12 },
        blur: 4,
        spread: 0,
        'offset-x': 0,
        'offset-y': 4,
        style: 'drop',
      },
      {
        color: { color: '#000000', opacity: 0.14 },
        blur: 2,
        spread: 0,
        'offset-x': 0,
        'offset-y': 2,
        style: 'drop',
      },
    ],
  },
  {
    name: 'Elevation 8',
    category: 'Effects',
    shadow: [
      {
        color: { color: '#000000', opacity: 0.12 },
        blur: 10,
        spread: 0,
        'offset-x': 0,
        'offset-y': 8,
        style: 'drop',
      },
      {
        color: { color: '#000000', opacity: 0.14 },
        blur: 6,
        spread: 0,
        'offset-x': 0,
        'offset-y': 4,
        style: 'drop',
      },
    ],
  },
  // Inner Shadows
  {
    name: 'Inset Soft',
    category: 'Effects',
    shadow: [
      {
        color: { color: '#000000', opacity: 0.2 },
        blur: 8,
        spread: 0,
        'offset-x': 0,
        'offset-y': 2,
        style: 'inner',
      },
    ],
  },
  {
    name: 'Inset Hard',
    category: 'Effects',
    shadow: [
      {
        color: { color: '#000000', opacity: 0.3 },
        blur: 4,
        spread: 0,
        'offset-x': 0,
        'offset-y': 1,
        style: 'inner',
      },
    ],
  },
  {
    name: 'Pressed Button',
    category: 'Effects',
    shadow: [
      {
        color: { color: '#000000', opacity: 0.25 },
        blur: 6,
        spread: 0,
        'offset-x': 0,
        'offset-y': 2,
        style: 'inner',
      },
    ],
  },
]

/**
 * Blur effect presets
 */
export const blurPresets: Preset[] = [
  {
    name: 'Light Blur',
    category: 'Effects',
    blur: {
      type: 'layer',
      value: 4,
      hidden: false,
    },
  },
  {
    name: 'Medium Blur',
    category: 'Effects',
    blur: {
      type: 'layer',
      value: 8,
      hidden: false,
    },
  },
  {
    name: 'Heavy Blur',
    category: 'Effects',
    blur: {
      type: 'layer',
      value: 16,
      hidden: false,
    },
  },
  {
    name: 'Extreme Blur',
    category: 'Effects',
    blur: {
      type: 'layer',
      value: 32,
      hidden: false,
    },
  },
  {
    name: 'Light Background Blur',
    category: 'Effects',
    blur: {
      type: 'background',
      value: 4,
      hidden: false,
    },
  },
  {
    name: 'Medium Background Blur',
    category: 'Effects',
    blur: {
      type: 'background',
      value: 8,
      hidden: false,
    },
  },
  {
    name: 'Heavy Background Blur',
    category: 'Effects',
    blur: {
      type: 'background',
      value: 16,
      hidden: false,
    },
  },
]

/**
 * Complete presets combining position, size, styling, and effects
 */
export function getCompletePresets(canvasWidth: number, canvasHeight: number): Preset[] {
  const cx = centerX(canvasWidth, 300)
  const cy = centerY(canvasHeight, 200)
  
  return [
    {
      name: 'Centered Card',
      category: 'Complete',
      x: cx,
      y: cy,
      width: 300,
      height: 200,
      fillColor: '#FFFFFF',
      fillOpacity: 1,
      borderRadius: 8,
      shadow: [
        {
          color: { color: '#000000', opacity: 0.2 },
          blur: 8,
          spread: 0,
          'offset-x': 2,
          'offset-y': 2,
          style: 'drop',
        },
      ],
      description: 'Centered white card with soft shadow',
    },
    {
      name: 'Button Primary',
      category: 'Complete',
      x: cx,
      y: cy + 250,
      width: 200,
      height: 50,
      borderRadius: 25,
      fillGradient: {
        type: 'linear',
        x1: 0,
        y1: 0,
        x2: 1,
        y2: 0,
        stops: [
          { offset: 0, color: { color: '#667EEA', opacity: 1 } },
          { offset: 1, color: { color: '#764BA2', opacity: 1 } },
        ],
      },
      shadow: [
        {
          color: { color: '#000000', opacity: 0.25 },
          blur: 12,
          spread: 0,
          'offset-x': 4,
          'offset-y': 4,
          style: 'drop',
        },
      ],
      description: 'Primary button with gradient and shadow',
    },
    {
      name: 'Hero Section',
      category: 'Complete',
      x: 0,
      y: 0,
      width: canvasWidth,
      height: 400,
      fillGradient: {
        type: 'linear',
        x1: 0,
        y1: 0,
        x2: 0,
        y2: 1,
        stops: [
          { offset: 0, color: { color: '#667EEA', opacity: 1 } },
          { offset: 1, color: { color: '#764BA2', opacity: 1 } },
        ],
      },
      blur: {
        type: 'layer',
        value: 4,
        hidden: false,
      },
      description: 'Full-width hero section with gradient',
    },
    {
      name: 'Glass Morphism',
      category: 'Complete',
      x: cx,
      y: cy,
      width: 300,
      height: 200,
      fillColor: '#FFFFFF',
      fillOpacity: 0.3,
      borderRadius: 16,
      blur: {
        type: 'background',
        value: 16,
        hidden: false,
      },
      shadow: [
        {
          color: { color: '#000000', opacity: 0.1 },
          blur: 8,
          spread: 0,
          'offset-x': 0,
          'offset-y': 4,
          style: 'drop',
        },
      ],
      description: 'Glass morphism effect with blur and transparency',
    },
    {
      name: 'Floating Card',
      category: 'Complete',
      x: cx,
      y: cy,
      width: 300,
      height: 200,
      fillColor: '#FFFFFF',
      fillOpacity: 1,
      borderRadius: 12,
      shadow: [
        {
          color: { color: '#000000', opacity: 0.15 },
          blur: 20,
          spread: 0,
          'offset-x': 0,
          'offset-y': 8,
          style: 'drop',
        },
      ],
      blur: {
        type: 'layer',
        value: 4,
        hidden: false,
      },
      description: 'Floating card with large shadow and light blur',
    },
    {
      name: 'Neon Glow',
      category: 'Complete',
      x: cx,
      y: cy,
      width: 200,
      height: 200,
      fillColor: '#FF00FF',
      fillOpacity: 0.8,
      borderRadius: 100,
      shadow: [
        {
          color: { color: '#FF00FF', opacity: 0.6 },
          blur: 20,
          spread: 0,
          'offset-x': 0,
          'offset-y': 0,
          style: 'drop',
        },
      ],
      blur: {
        type: 'layer',
        value: 8,
        hidden: false,
      },
      description: 'Neon glow effect with colored shadow',
    },
    {
      name: 'Pressed Button',
      category: 'Complete',
      x: cx,
      y: cy + 250,
      width: 150,
      height: 40,
      borderRadius: 20,
      fillGradient: {
        type: 'linear',
        x1: 0,
        y1: 0,
        x2: 0,
        y2: 1,
        stops: [
          { offset: 0, color: { color: '#E0E0E0', opacity: 1 } },
          { offset: 1, color: { color: '#BDBDBD', opacity: 1 } },
        ],
      },
      shadow: [
        {
          color: { color: '#000000', opacity: 0.25 },
          blur: 6,
          spread: 0,
          'offset-x': 0,
          'offset-y': 2,
          style: 'inner',
        },
      ],
      description: 'Pressed button with inner shadow',
    },
  ]
}

/**
 * Get all presets organized by category
 */
export function getAllPresets(canvasWidth: number, canvasHeight: number): Preset[] {
  return [
    ...getPositionPresets(canvasWidth, canvasHeight),
    ...sizePresets,
    ...colorPresets,
    ...gradientPresets,
    ...shadowPresets,
    ...blurPresets,
    ...getCompletePresets(canvasWidth, canvasHeight),
  ]
}

/**
 * Get presets by category
 */
export function getPresetsByCategory(
  category: Preset['category'],
  canvasWidth: number,
  canvasHeight: number
): Preset[] {
  const allPresets = getAllPresets(canvasWidth, canvasHeight)
  return allPresets.filter((p) => p.category === category)
}


