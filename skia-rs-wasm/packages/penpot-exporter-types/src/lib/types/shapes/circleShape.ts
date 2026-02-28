import type { LayoutChildAttributes } from './layout';
import type {
  ShapeAttributes,
  ShapeBaseAttributes,
  ShapeGeomAttributes
} from './shape';

export type CircleShape = ShapeBaseAttributes &
  ShapeGeomAttributes &
  ShapeAttributes &
  CircleAttributes &
  LayoutChildAttributes;

type CircleAttributes = {
  type: 'circle';
};
