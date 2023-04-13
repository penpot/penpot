import Point from './Point'
import Rect from './Rect'

@unmanaged
export default class ResizeOutput {
  origin: Point = new Point()
  vector: Point = new Point()
  displacement: Point = new Point()

  toString(): string {
    return `ResizeOutput(${this.origin}, ${this.vector}, ${this.displacement})`
  }
}
