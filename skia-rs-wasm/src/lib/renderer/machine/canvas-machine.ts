/**
 * Canvas interaction state machine (XState v5). Replaces scattered Zustand booleans
 * (isMoving, isResizing, …) with explicit states; RxJS handlers run as invoked actors.
 */

import { assign, fromObservable, setup } from 'xstate'
import { startMoveSelected } from '../handlers/move'
import { startRotateSelected } from '../handlers/rotate'
import { startResizeSelected } from '../handlers/resize'
import { handleAreaSelection } from '../handlers/selection'
import { handleDrawRect } from '../handlers/draw-shape'
import type { Point, ResizeHandlePosition } from '../types'

export type DrawTool = 'rect'

export interface CanvasContext {
  resizeHandle: ResizeHandlePosition | null
  rotationCorner: ResizeHandlePosition | null
  drawTool: DrawTool | null
  areaSelectionAppend: boolean
  areaSelectionRemove: boolean
}

export type CanvasEvent =
  | { type: 'POINTER_DOWN_ON_SELECTION'; position: Point }
  | { type: 'POINTER_DOWN_ON_CORNER'; handle: ResizeHandlePosition; position: Point }
  | { type: 'POINTER_DOWN_ON_ROTATION'; corner: ResizeHandlePosition; position: Point }
  | { type: 'POINTER_DOWN_ON_CANVAS'; append: boolean; remove: boolean }
  | { type: 'POINTER_DOWN_DRAW' }
  | { type: 'PAN_START' }
  | { type: 'PAN_END' }
  | { type: 'DRAW_TOOL_ACTIVATE'; tool: DrawTool }
  | { type: 'DRAW_TOOL_DEACTIVATE' }

const canvasMachineSetup = setup({
  types: {
    context: {} as CanvasContext,
    events: {} as CanvasEvent,
  },
  actors: {
    moveActor: fromObservable(({ input }: { input: { position: Point } }) => startMoveSelected(input.position)),
    rotateActor: fromObservable(({ input }: { input: { position: Point } }) =>
      startRotateSelected(input.position),
    ),
    resizeActor: fromObservable(({ input }: { input: { position: Point; handle: ResizeHandlePosition } }) =>
      startResizeSelected(input.position, input.handle),
    ),
    selectActor: fromObservable(
      ({ input }: { input: { append: boolean; remove: boolean; ignoreGroups?: boolean } }) =>
        handleAreaSelection(input.append, input.remove, input.ignoreGroups),
    ),
    drawActor: fromObservable(() => handleDrawRect()),
  },
})

export const canvasMachine = canvasMachineSetup.createMachine({
  id: 'canvas',
  initial: 'idle',
  context: {
    resizeHandle: null,
    rotationCorner: null,
    drawTool: null,
    areaSelectionAppend: false,
    areaSelectionRemove: false,
  },
  on: {
    DRAW_TOOL_ACTIVATE: {
      actions: assign({ drawTool: ({ event }) => event.tool }),
    },
    DRAW_TOOL_DEACTIVATE: {
      actions: assign({ drawTool: () => null }),
    },
  },
  states: {
    idle: {
      on: {
        POINTER_DOWN_ON_SELECTION: { target: 'moving' },
        POINTER_DOWN_ON_CORNER: {
          target: 'resizing',
          actions: assign({ resizeHandle: ({ event }) => event.handle }),
        },
        POINTER_DOWN_ON_ROTATION: {
          target: 'rotating',
          actions: assign({ rotationCorner: ({ event }) => event.corner }),
        },
        POINTER_DOWN_ON_CANVAS: {
          target: 'selecting',
          actions: assign({
            areaSelectionAppend: ({ event }) => event.append,
            areaSelectionRemove: ({ event }) => event.remove,
          }),
        },
        POINTER_DOWN_DRAW: { target: 'drawingShape' },
        PAN_START: { target: 'panning' },
      },
    },
    moving: {
      invoke: {
        src: 'moveActor',
        input: ({ event }) =>
          event.type === 'POINTER_DOWN_ON_SELECTION'
            ? { position: event.position }
            : { position: { x: 0, y: 0 } },
        onDone: { target: 'idle' },
        onError: { target: 'idle' },
      },
    },
    rotating: {
      invoke: {
        src: 'rotateActor',
        input: ({ event }) =>
          event.type === 'POINTER_DOWN_ON_ROTATION'
            ? { position: event.position }
            : { position: { x: 0, y: 0 } },
        onDone: {
          target: 'idle',
          actions: assign({ rotationCorner: () => null }),
        },
        onError: {
          target: 'idle',
          actions: assign({ rotationCorner: () => null }),
        },
      },
    },
    resizing: {
      invoke: {
        src: 'resizeActor',
        input: ({ event }) =>
          event.type === 'POINTER_DOWN_ON_CORNER'
            ? { position: event.position, handle: event.handle }
            : { position: { x: 0, y: 0 }, handle: 'right' as ResizeHandlePosition },
        onDone: {
          target: 'idle',
          actions: assign({ resizeHandle: () => null }),
        },
        onError: {
          target: 'idle',
          actions: assign({ resizeHandle: () => null }),
        },
      },
    },
    selecting: {
      invoke: {
        src: 'selectActor',
        input: ({ context }) => ({
          append: context.areaSelectionAppend,
          remove: context.areaSelectionRemove,
        }),
        onDone: { target: 'idle' },
        onError: { target: 'idle' },
      },
    },
    drawingShape: {
      invoke: {
        src: 'drawActor',
        onDone: { target: 'idle' },
        onError: { target: 'idle' },
      },
    },
    panning: {
      on: {
        PAN_END: { target: 'idle' },
      },
    },
  },
})
