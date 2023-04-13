import Matrix from './Matrix'
import Point from './Point'
import ModifierType from './ModifierType'
import { degreesToRadians } from './Angle';

@unmanaged
export default class Modifier {
  center: Point = new Point();
  origin: Point = new Point();
  vector: Point = new Point();
  transform: Matrix = new Matrix();
  transformInverse: Matrix = new Matrix();
  shouldTransform: u32 = 0;
  rotation: f32 = 0;
  type: ModifierType = ModifierType.MOVE;
  order: i32 = 0;

  // Computed matrix
  matrix: Matrix = new Matrix();

  compute(): Modifier {
    this.matrix.identity()
    if (this.type === ModifierType.MOVE) {
      this.matrix.setTranslation(this.vector)
    } else if (this.type === ModifierType.RESIZE) {
      if (this.shouldTransform) {
        this.origin.transform(this.transformInverse)
        this.matrix.multiply(this.transform)
      }
      this.matrix
        .translate(this.origin)
        .scale(this.vector)
        .translate(this.origin.negate())
      if (this.shouldTransform) {
        this.matrix.multiply(this.transformInverse)
      }
    } else if (this.type === ModifierType.ROTATION) {
      this.matrix
        .translate(this.center)
        .rotate(this.rotation)
        .translate(this.center.negate())
    }
    return this
  }
}
