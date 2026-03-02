import type { Change } from 'skia-rs-wasm/common'
import type { PenpotPage } from 'penpot-exporter/lib'
import {
  transformSceneNode,
  SUPPORTED_SCENE_NODE_TYPES,
  transformId,
} from 'penpot-exporter/lib'
import {
  getFrameNode,
  buildDelObjChange,
  buildAddObjChange,
  buildModObjChange,
} from './node-change-to-changes'

/**
 * Figma adapter: translates Figma plugin updates (e.g. PageNode.on('change'),
 * documentchange events) into skia-rs-wasm–compatible data (PenpotPage, Change[]).
 * Consumed by plugin UIs such as figma_plugin_fe to drive the canvas.
 */

/**
 * Converts a full Figma PageNode snapshot into a PenpotPage.
 * Stub: returns a minimal placeholder until translation is implemented.
 */
export function translatePage(_figmaPage: PageNode): PenpotPage {
  return {
    name: '',
    children: [],
  }
}

/**
 * Converts a Figma document change event into incremental Change[] for the worker.
 * Stub: returns an empty array until translation is implemented.
 */
export function translateChange(_event: DocumentChangeEvent): Change[] {
  return []
}

export interface TranslateNodeChangeOptions {
  pageId?: string
}

/**
 * Converts a Figma NodeChangeEvent (nodechange) into incremental Change[] for the worker.
 * Runs in plugin context (requires SceneNode). Skips node types not supported by
 * transformSceneNode in penpot-exporter/lib, and shape types that the wasm cannot draw.
 */
export async function translateNodeChange(
  event: NodeChangeEvent,
  options?: TranslateNodeChangeOptions
): Promise<Change[]> {
  const changes: Change[] = []
  const pageId = options?.pageId
  const nodeChanges = event?.nodeChanges ?? []
  if (nodeChanges.length === 0) return []

  for (const change of nodeChanges) {
    const node = change.node
    if (!node) continue

    if (change.type === 'DELETE') {
      const id = 'removed' in node ? node.id : (node as SceneNode).id
      if (id) {
        const penpotId = transformId({ id } as SceneNode)
        changes.push(buildDelObjChange(penpotId, pageId))
      }
      continue
    }

    if (change.type === 'CREATE') {
      if ('removed' in node && node.removed) continue
      const sceneNode = node as SceneNode
      if (!SUPPORTED_SCENE_NODE_TYPES.has(sceneNode.type)) {
        console.warn(`Unsupported node type: ${sceneNode.type}`)
        continue
      }
      const penpotNode = await transformSceneNode(sceneNode)
      const parent = sceneNode.parent
      if (!parent || (parent as BaseNode).type === 'DOCUMENT') continue
      const parentScene = parent as SceneNode
      const parentId = transformId(parentScene)
      const frameNode = getFrameNode(sceneNode)
      const frameId = frameNode ? transformId(frameNode) : parentId
      const index =
        'children' in parentScene && Array.isArray(parentScene.children)
          ? parentScene.children.indexOf(sceneNode)
          : null
      const id = penpotNode?.id ?? transformId(sceneNode)
      changes.push(
        buildAddObjChange(id, penpotNode!, parentId, frameId, index, pageId)
      )
      continue
    }

    if (change.type === 'PROPERTY_CHANGE') {
      if ('removed' in node && node.removed) continue
      const sceneNode = node as SceneNode
      if (!SUPPORTED_SCENE_NODE_TYPES.has(sceneNode.type)) {
        console.warn(`Unsupported node type: ${sceneNode.type}`)
        continue
      }
      const penpotNode = await transformSceneNode(sceneNode, { skipChildren: true })
      const id = transformId(sceneNode)
      const value = penpotNode as Record<string, unknown>
      changes.push(buildModObjChange(id, value, pageId))
    }
  }

  return changes
}
