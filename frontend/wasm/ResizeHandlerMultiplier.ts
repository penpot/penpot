import Point from './Point'
import ResizeHandler from './ResizeHandler'

export const ResizeHandlerMultiplier: Array<Point> = [
  new Point(1, 0), /* [ResizeHandler.RIGHT]: */
  new Point(1, 1), /* [ResizeHandler.BOTTOM_RIGHT]: */
  new Point(0, 1), /* [ResizeHandler.BOTTOM]: */
  new Point(-1, 1), /* [ResizeHandler.BOTTOM_LEFT]: */
  new Point(-1, 0), /* [ResizeHandler.LEFT]: */
  new Point(-1, -1), /* [ResizeHandler.TOP_LEFT]: */
  new Point(0, -1), /* [ResizeHandler.TOP]: */
  new Point(1, -1), /* [ResizeHandler.TOP_RIGHT]: */
]

export default ResizeHandlerMultiplier
