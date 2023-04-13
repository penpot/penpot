import Matrix from './Matrix'
import Point from './Point'
import Rect from './Rect'

@unmanaged
export default class ResizeInput {
  rotation: f32 = 0
  handler: u32 = 0
  shouldLock: u32 = 0
  shouldCenter: u32 = 0
  selRect: Rect = new Rect()
  start: Point = new Point()
  current: Point = new Point()
  snap: Point = new Point()
  shouldTransform: u32 = 0
  transform: Matrix = new Matrix()
  transformInverse: Matrix = new Matrix()
}
