import type {
  ShapeAttributes,
  ShapeBaseAttributes,
  ShapeGeomAttributes
} from './shape';
import type { Children } from '../utils/children';
import type { Uuid } from '../utils/uuid';

export type GroupShape = ShapeBaseAttributes &
  ShapeGeomAttributes &
  ShapeAttributes &
  GroupAttributes &
  Children;

type GroupAttributes = {
  type: 'group';
  shapes?: Uuid[];
};
