import Point from './Point';
import Matrix from './Matrix';

@unmanaged
export default class TransformInput {
  matrix: Matrix = new Matrix();
  shouldTransform: u32 = 0;
  transform: Matrix = new Matrix();
  transformInverse: Matrix = new Matrix();
  origin: Point = new Point();
  vector: Point = new Point();
  center: Point = new Point();
  rotation: f32 = 0;
}
