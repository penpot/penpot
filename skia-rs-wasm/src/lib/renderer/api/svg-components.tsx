/**
 * React components for SVG rendering
 * Replicates ClojureScript React components for server-side rendering
 */

import React, { createContext, useContext, useMemo } from 'react'
import type { Matrix, PenpotNode } from 'penpot-exporter/lib'
import type { SvgContent } from '../types'
import { isSvgContentTree, isSvgContentString, isSvgContent } from '../types'
import { generateIdMapping, formatTransform, isSvgTag, isGraphicElement, svgTransformMatrix } from './svg-utils'
import { addFillProps } from './svg-attrs'

/**
 * Context for SVG ID mapping
 */
const SvgIdsContext = createContext<Map<string, string> | null>(null)

/**
 * Context for render ID (for unique identifiers)
 */
const RenderIdContext = createContext<string>('')

/**
 * Context for current SVG root ID
 */
const CurrentSvgRootIdContext = createContext<string>('')

/**
 * SvgRoot component - handles root <svg> element
 */
interface SvgRootProps {
  shape: PenpotNode
  children: React.ReactNode
}

export function SvgRoot({ shape, children }: SvgRootProps): React.ReactElement {
  const shapeGeom = shape as { x?: number; y?: number; width?: number; height?: number; content?: unknown }
  const x = shapeGeom.x ?? 0
  const y = shapeGeom.y ?? 0
  const width = shapeGeom.width ?? 100
  const height = shapeGeom.height ?? 100

  const content = shapeGeom.content as SvgContent | Record<string, unknown> | undefined
  const idsMapping = useMemo(() => {
    if (isSvgContent(content) && isSvgContentTree(content)) {
      return generateIdMapping(content)
    }
    return new Map<string, string>()
  }, [content])

  const renderId = useContext(RenderIdContext) || ''

  const svgProps = useMemo(() => {
    const props: Record<string, unknown> = {}
    addFillProps(props, shape, renderId)
    props.x = x
    props.y = y
    props.width = width
    props.height = height
    props.preserveAspectRatio = 'none'
    // Remove transform from svg element itself
    delete props.transform
    return props
  }, [shape, renderId, x, y, width, height])

  const transformStr = formatTransform(shape)

  return (
    <SvgIdsContext.Provider value={idsMapping}>
      <g className="svg-raw" transform={transformStr || undefined}>
        <svg {...svgProps}>{children}</svg>
      </g>
    </SvgIdsContext.Provider>
  )
}

/**
 * SvgElement component - renders individual SVG elements
 */
interface SvgElementProps {
  shape: PenpotNode
  children: React.ReactNode
}

export function SvgElement({ shape, children }: SvgElementProps): React.ReactElement {
  const idsMapping = useContext(SvgIdsContext) || new Map()
  const renderId = useContext(RenderIdContext) || ''

  const content = (shape as { content?: unknown }).content as SvgContent | Record<string, unknown> | undefined
  if (!isSvgContent(content) || !isSvgContentTree(content)) {
    return <>{children}</>
  }

  const tag = content.tag
  const attrs = content.attrs || {}

  // Apply ID remapping
  const updatedAttrs = useMemo(() => {
    let updated = { ...attrs }
    const elementId = updated.id
    if (elementId && typeof elementId === 'string' && idsMapping.has(elementId)) {
      updated = { ...updated, id: idsMapping.get(elementId) }
    }

    // Replace IDs in other attributes (simplified - full implementation would recurse)
    for (const [key, value] of Object.entries(updated)) {
      if (typeof value === 'string' && value.includes('#')) {
        // Simple ID replacement in string values
        for (const [oldId, newId] of idsMapping.entries()) {
          updated[key] = (value as string).replace(`#${oldId}`, `#${newId}`)
        }
      }
    }

    return updated
  }, [attrs, idsMapping])

  // Handle transforms for graphic elements
  const finalAttrs = useMemo(() => {
    if (isGraphicElement(tag)) {
      // For graphic elements, combine SVG transform matrix with existing transform from attrs
      // Convert SelRect (x1, y1, x2, y2) to format expected by svgTransformMatrix (x, y, width, height)
      const shapeWithViewbox = shape as { svgViewbox?: { x: number; y: number; width: number; height: number }; transform?: Matrix; type?: string }
      const shapeForTransform: {
        svgViewbox?: { x: number; y: number; width: number; height: number }
        selrect?: { x: number; y: number; width: number; height: number }
        transform?: Matrix
        type?: string
      } = {
        svgViewbox: shapeWithViewbox.svgViewbox,
        transform: shapeWithViewbox.transform,
        type: shapeWithViewbox.type,
      }
      if (shape.selrect) {
        shapeForTransform.selrect = {
          x: shape.selrect.x1,
          y: shape.selrect.y1,
          width: shape.selrect.x2 - shape.selrect.x1,
          height: shape.selrect.y2 - shape.selrect.y1,
        }
      }
      const svgTransform = svgTransformMatrix(shapeForTransform)
      const existingTransform = updatedAttrs.transform || ''
      if (svgTransform || existingTransform) {
        return { ...updatedAttrs, transform: `${svgTransform} ${existingTransform}`.trim() }
      }
    } else {
      // Remove transform for non-graphic elements
      // eslint-disable-next-line @typescript-eslint/no-unused-vars
      const { transform, ...rest } = updatedAttrs
      return rest
    }
    return updatedAttrs
  }, [tag, updatedAttrs, shape])

  const props = useMemo(() => {
    return addFillProps(finalAttrs, shape, renderId)
  }, [finalAttrs, shape, renderId])

  // Convert kebab-case to camelCase for React props
  const reactProps: Record<string, unknown> = {}
  for (const [key, value] of Object.entries(props)) {
    const camelKey = key.replace(/-([a-z])/g, (_, letter) => letter.toUpperCase())
    reactProps[camelKey] = value
  }

  return React.createElement(tag, reactProps, children)
}

/**
 * SvgRawShape component - main component that handles content structure
 */
interface SvgRawShapeProps {
  shape: PenpotNode
  children?: PenpotNode[]
}

export function SvgRawShape({ shape, children: childShapes }: SvgRawShapeProps): React.ReactElement | string | null {
  const content = (shape as { content?: unknown }).content as SvgContent | Record<string, unknown> | undefined

  if (!content) {
    return null
  }

  // Check if content is SvgContent (not TextContent or PathContent)
  if (!isSvgContent(content)) {
    return null
  }

  // Handle string content (leaf node)
  if (isSvgContentString(content)) {
    return content
  }

  // Handle tree content
  if (!isSvgContentTree(content)) {
    return null
  }

  const tag = content.tag
  const currentSvgRootId = useContext(CurrentSvgRootIdContext) || ''

  // Handle style tag
  if (tag === 'style') {
    const styleContent = content.content
    if (Array.isArray(styleContent)) {
      const styleText = styleContent
        .map((item) => (typeof item === 'string' ? item : ''))
        .join('\n')
      return <style>{`#shape-${currentSvgRootId} { ${styleText} }`}</style>
    }
    return <style></style>
  }

  // Render children from content tree
  const renderChildren = (): React.ReactNode => {
    if (content.content && Array.isArray(content.content)) {
      return content.content.map((childContent, index) => {
        if (isSvgContentString(childContent)) {
          return <React.Fragment key={index}>{childContent}</React.Fragment>
        }
        if (isSvgContentTree(childContent)) {
          // Create a minimal shape node for the child content
          const childShape = {
            ...shape,
            id: `${shape.id}-${index}`,
            content: childContent,
          } as unknown as PenpotNode
          return <SvgRawShape key={index} shape={childShape} />
        }
        return null
      })
    }
    // Fallback: try to use childShapes if provided
    if (childShapes) {
      return childShapes.map((child) => (
        <SvgRawShape key={child.id} shape={child} />
      ))
    }
    return null
  }

  // Handle SVG root
  if (tag === 'svg') {
    return <SvgRoot shape={shape}>{renderChildren()}</SvgRoot>
  }

  // Handle valid SVG tags
  if (isSvgTag(tag)) {
    return <SvgElement shape={shape}>{renderChildren()}</SvgElement>
  }

  return null
}

/**
 * ObjectSvg component - root wrapper component
 */
interface ObjectSvgProps {
  shape: PenpotNode
}

export function ObjectSvg({ shape }: ObjectSvgProps): React.ReactElement {
  const renderId = useMemo(() => {
    // Generate a simple render ID from shape ID
    return shape.id.substring(0, 8)
  }, [shape.id])

  const currentSvgRootId = useMemo(() => {
    return shape.id
  }, [shape.id])

  let content: React.ReactNode

  if ((shape as { type: string }).type === 'svg-raw') {
    // The content tree structure should contain nested elements
    // Child shapes from shape.shapes are IDs, but we render from content tree
    content = (
      <CurrentSvgRootIdContext.Provider value={currentSvgRootId}>
        <SvgRawShape shape={shape} />
      </CurrentSvgRootIdContext.Provider>
    )
  } else {
    // For non-svg-raw shapes, return minimal wrapper
    content = null
  }

  return (
    <RenderIdContext.Provider value={renderId}>
      <svg
        version="1.1"
        xmlns="http://www.w3.org/2000/svg"
        xmlnsXlink="http://www.w3.org/1999/xlink"
        fill="none"
      >
        {content}
      </svg>
    </RenderIdContext.Provider>
  )
}

