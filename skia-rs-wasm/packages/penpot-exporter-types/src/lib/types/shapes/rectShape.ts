import type { LayoutChildAttributes } from './layout';
import type {
  ShapeAttributes,
  ShapeBaseAttributes,
  ShapeGeomAttributes
} from './shape';

export type RectShape = ShapeBaseAttributes &
  ShapeGeomAttributes &
  ShapeAttributes &
  RectAttributes &
  LayoutChildAttributes;

type RectAttributes = {
  type: 'rect';
};
