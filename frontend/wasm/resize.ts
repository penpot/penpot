import Rect from './Rect'
import Point from './Point'
import Matrix from './Matrix'
import ResizeHandler from './ResizeHandler'
import ResizeHandlerMultiplier from './ResizeHandlerMultiplier'
import ResizeInput from './ResizeInput'
import ResizeOutput from './ResizeOutput'

// Input data
export const resizeInput: ResizeInput = new ResizeInput()

// Output data
export const resizeOutput: ResizeOutput = new ResizeOutput()

// Handlers classified by its constraints.
const anyLeft: Array<ResizeHandler> = [
  ResizeHandler.LEFT,
  ResizeHandler.TOP_LEFT,
  ResizeHandler.BOTTOM_LEFT
]
const anyRight: Array<ResizeHandler> = [
  ResizeHandler.RIGHT,
  ResizeHandler.TOP_RIGHT,
  ResizeHandler.BOTTOM_RIGHT,
]
const anyTop: Array<ResizeHandler> = [
  ResizeHandler.TOP,
  ResizeHandler.TOP_RIGHT,
  ResizeHandler.TOP_LEFT,
]
const anyBottom: Array<ResizeHandler> = [
  ResizeHandler.BOTTOM,
  ResizeHandler.BOTTOM_RIGHT,
  ResizeHandler.BOTTOM_LEFT,
]

// Intermediate values
const start: Point = new Point()
const current: Point = new Point()
const size: Point = new Point()
const center: Point = new Point()
const multiplier: Point = new Point()
const delta: Point = new Point()
const centerNegatedTransformMatrix: Matrix = new Matrix()
const centerTransformMatrix: Matrix = new Matrix()
const transformMatrix: Matrix = new Matrix()

/**
 * Transform a point along the center of the selection
 * rectangle.
 *
 * @param point
 * @param matrix
 */
function transformPointCenter(point: Point, matrix: Matrix): void
{
  transformMatrix
    .copy(centerTransformMatrix)
    .multiply(matrix)
    .multiply(centerNegatedTransformMatrix)

  point
    .transform(transformMatrix)
}

/**
 * Calculate all derived data and transforms vectors
 * to resize elements.
 */
export function resize(): void
{
  current.reset()
  delta.reset()

  size.copy(resizeInput.selRect.size)

  // update center
  center.set(
    resizeInput.selRect.centerX,
    resizeInput.selRect.centerY
  )

  // create transform matrices.
  if (resizeInput.shouldTransform) {
    centerNegatedTransformMatrix
      .identity()
      .setNegatedTranslation(center)

    centerTransformMatrix
      .identity()
      .setTranslation(center)
  }

  // update handler origin
  switch (resizeInput.handler)
  {
    case ResizeHandler.RIGHT: resizeOutput.origin.set(resizeInput.selRect.left, resizeInput.selRect.centerY); break
    case ResizeHandler.BOTTOM: resizeOutput.origin.set(resizeInput.selRect.centerY, resizeInput.selRect.top); break
    case ResizeHandler.LEFT: resizeOutput.origin.set(resizeInput.selRect.right, resizeInput.selRect.centerY); break
    case ResizeHandler.TOP: resizeOutput.origin.set(resizeInput.selRect.centerY, resizeInput.selRect.bottom); break
    case ResizeHandler.TOP_RIGHT: resizeOutput.origin.set(resizeInput.selRect.left, resizeInput.selRect.bottom); break
    case ResizeHandler.TOP_LEFT: resizeOutput.origin.set(resizeInput.selRect.right, resizeInput.selRect.bottom); break
    case ResizeHandler.BOTTOM_RIGHT: resizeOutput.origin.set(resizeInput.selRect.left, resizeInput.selRect.top); break
    case ResizeHandler.BOTTOM_LEFT: resizeOutput.origin.set(resizeInput.selRect.right, resizeInput.selRect.top); break
  }

  // update handler multiplier
  multiplier.copy(ResizeHandlerMultiplier[resizeInput.handler])

  // update vector
  start.copy(resizeInput.start)
  if (resizeInput.shouldTransform) {
    transformPointCenter(start, resizeInput.transformInverse)
  }

  // fix-init-point
  if (anyLeft.includes(resizeInput.handler)) {
    start.x = resizeInput.selRect.left
  }

  if (anyRight.includes(resizeInput.handler)) {
    start.x = resizeInput.selRect.right
  }

  if (anyTop.includes(resizeInput.handler)) {
    start.y = resizeInput.selRect.top
  }

  if (anyBottom.includes(resizeInput.handler)) {
    start.y = resizeInput.selRect.bottom
  }

  current.copy(
    resizeInput.rotation === 0
      ? resizeInput.snap
      : resizeInput.current
  )

  if (resizeInput.shouldTransform) {
    transformPointCenter(current, resizeInput.transformInverse)
  }

  delta.copy(current)
  delta.subtract(start)
  delta.multiply(multiplier)

  resizeOutput.vector.copy(size)
  resizeOutput.vector.add(delta)
  resizeOutput.vector.divide(size)
  resizeOutput.vector.clamp(0.001, Infinity) // gpt/no-zeros

  // lock proportions?
  if (resizeInput.shouldLock) {
    if (
      resizeInput.handler === ResizeHandler.LEFT ||
      resizeInput.handler === ResizeHandler.RIGHT
    ) {
      resizeOutput.vector.set(resizeOutput.vector.x, resizeOutput.vector.x)
    } else if (
      resizeInput.handler === ResizeHandler.TOP ||
      resizeInput.handler === ResizeHandler.BOTTOM
    ) {
      resizeOutput.vector.set(resizeOutput.vector.y, resizeOutput.vector.y)
    } else {
      const v: f32 = f32(Math.max(resizeOutput.vector.x, resizeOutput.vector.y))
      resizeOutput.vector.set(v, v)
    }
  }

  if (resizeInput.shouldCenter) {
    resizeOutput.displacement
      .copy(center)
      .subtract(resizeOutput.origin)
      .multiply(resizeOutput.vector)
      .add(resizeOutput.origin)
      .subtract(center)
      .multiply(ResizeHandlerMultiplier[ResizeHandler.TOP_LEFT])
      .transform(resizeInput.transform)
  }

  if (resizeInput.shouldTransform) {
    transformPointCenter(resizeOutput.origin, resizeInput.transform)
  }
  resizeOutput.origin.add(resizeOutput.displacement)
}
