/**
 * Quadtree spatial index implementation
 * Ported from frontend/src/app/util/quadtree.js
 */

import type { Selrect } from 'penpot-exporter/types'
import { makeSelrect } from './types'

export interface QuadtreeNode<T = unknown> {
  id: string
  bounds: Selrect
  data: T
}

export class Quadtree<T = unknown> {
  maxObjects: number
  maxLevels: number
  level: number
  bounds: Selrect
  objects: QuadtreeNode<T>[]
  indexes: Quadtree<T>[]

  constructor(bounds: Selrect, maxObjects: number = 10, maxLevels: number = 4, level: number = 0) {
    this.maxObjects = maxObjects
    this.maxLevels = maxLevels
    this.level = level
    this.bounds = bounds
    this.objects = []
    this.indexes = []
  }

  split(): void {
    const nextLevel = this.level + 1
    const subWidth = this.bounds.width / 2
    const subHeight = this.bounds.height / 2
    const x = this.bounds.x
    const y = this.bounds.y

    // top-right quad
    this.indexes[0] = new Quadtree<T>(
      makeSelrect(x + subWidth, y, subWidth, subHeight),
      this.maxObjects,
      this.maxLevels,
      nextLevel
    )

    // top-left quad
    this.indexes[1] = new Quadtree<T>(
      makeSelrect(x, y, subWidth, subHeight),
      this.maxObjects,
      this.maxLevels,
      nextLevel
    )

    // bottom-left quad
    this.indexes[2] = new Quadtree<T>(
      makeSelrect(x, y + subHeight, subWidth, subHeight),
      this.maxObjects,
      this.maxLevels,
      nextLevel
    )

    // bottom-right quad
    this.indexes[3] = new Quadtree<T>(
      makeSelrect(x + subWidth, y + subHeight, subWidth, subHeight),
      this.maxObjects,
      this.maxLevels,
      nextLevel
    )
  }

  *getIndexes(rect: Selrect): Generator<Quadtree<T>> {
    const verticalMidpoint = this.bounds.x + this.bounds.width / 2
    const horizontalMidpoint = this.bounds.y + this.bounds.height / 2

    const startIsNorth = rect.y < horizontalMidpoint
    const startIsWest = rect.x < verticalMidpoint
    const endIsEast = rect.x + rect.width > verticalMidpoint
    const endIsSouth = rect.y + rect.height > horizontalMidpoint

    // top-right quad
    if (startIsNorth && endIsEast) {
      yield this.indexes[0]
    }

    // top-left quad
    if (startIsWest && startIsNorth) {
      yield this.indexes[1]
    }

    // bottom-left quad
    if (startIsWest && endIsSouth) {
      yield this.indexes[2]
    }

    // bottom-right quad
    if (endIsEast && endIsSouth) {
      yield this.indexes[3]
    }
  }

  insert(node: QuadtreeNode<T>): void {
    // If we have subindexes, call insert on matching subindexes
    if (this.indexes.length > 0) {
      for (const index of this.getIndexes(node.bounds)) {
        index.insert(node)
      }
    } else {
      // Otherwise, store object here
      this.objects.push(node)

      // Max objects reached
      if (this.objects.length > this.maxObjects && this.level < this.maxLevels) {
        // Split if we don't already have subindexes
        if (this.indexes.length === 0) {
          this.split()
        }

        // Add all objects to their corresponding subnode
        for (const obj of this.objects) {
          for (const index of this.getIndexes(obj.bounds)) {
            index.insert(obj)
          }
        }

        this.objects = []
      }
    }
  }

  count(): number {
    if (this.indexes.length === 0) {
      return this.objects.length
    } else {
      let sum = 0
      for (const index of this.indexes) {
        sum += index.count()
      }
      return sum
    }
  }

  *search(rect: Selrect): Generator<QuadtreeNode<T>> {
    if (this.indexes.length === 0) {
      yield* this.objects
    } else {
      for (const index of this.getIndexes(rect)) {
        yield* index.search(rect)
      }
    }
  }

  clear(): void {
    this.objects = []
    this.indexes = []
  }

  getObjects(): QuadtreeNode<T>[] {
    return this.objects
  }
}

// Helper functions matching the original API
export function create<T = unknown>(bounds: Selrect): Quadtree<T> {
  return new Quadtree<T>(bounds, 10, 4, 0)
}

export function insert<T>(
  index: Quadtree<T>,
  id: string,
  bounds: Selrect,
  data: T
): Quadtree<T> {
  const node: QuadtreeNode<T> = { id, bounds, data }
  index.insert(node)
  return index
}

export function clear<T>(index: Quadtree<T>): Quadtree<T> {
  index.clear()
  return index
}

export function* search<T>(
  index: Quadtree<T>,
  rect: Selrect
): Generator<QuadtreeNode<T>> {
  const tmp = new Set<string>()
  for (const item of index.search(rect)) {
    if (!tmp.has(item.id)) {
      tmp.add(item.id)
      yield item
    }
  }
}

export function remove<T>(index: Quadtree<T>, id: string): Quadtree<T> {
  const result = create<T>(index.bounds)

  for (const node of index.objects) {
    if (node.id !== id) {
      insert(result, node.id, node.bounds, node.data)
    }
  }

  return result
}

export function removeAll<T>(index: Quadtree<T>, ids: Set<string>): Quadtree<T> {
  const result = create<T>(index.bounds)

  for (const node of search(index, index.bounds)) {
    if (!ids.has(node.id)) {
      insert(result, node.id, node.bounds, node.data)
    }
  }

  return result
}

