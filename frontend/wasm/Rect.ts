import Point from './Point'

@unmanaged
export default class Rect {
  position: Point = new Point()
  size: Point = new Point()

  constructor(position: Point = new Point(), size: Point = new Point()) {
    this.position = position
    this.size = size
  }

  get left(): f32 {
    return this.position.x
  }

  get right(): f32 {
    return this.position.x + this.size.x
  }

  get top(): f32 {
    return this.position.y
  }

  get bottom(): f32 {
    return this.position.y + this.size.y
  }

  get x(): f32 {
    return this.position.x
  }

  get y(): f32 {
    return this.position.y
  }

  get width(): f32 {
    return this.size.x
  }

  get height(): f32 {
    return this.size.y
  }

  get centerX(): f32 {
    return this.position.x + this.size.x / 2
  }

  get centerY(): f32 {
    return this.position.y + this.size.y / 2
  }

  clone(deep: boolean): Rect {
    return new Rect(
      deep ? this.position.clone() : this.position,
      deep ? this.size.clone() : this.size
    )
  }

  contains(point: Point): boolean {
    return point.x > this.left
        && point.x < this.right
        && point.y > this.top
        && point.y < this.bottom
  }

  intersects(other: Rect): boolean {
    if (this.left > other.right || this.right < other.left)
      return false

    if (this.top > other.bottom || this.bottom < other.top)
      return false

    return true
  }
}
