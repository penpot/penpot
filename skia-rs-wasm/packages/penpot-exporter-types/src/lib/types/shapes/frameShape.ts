import type { LayoutAttributes, LayoutChildAttributes } from './layout';
import type {
  ShapeAttributes,
  ShapeBaseAttributes,
  ShapeGeomAttributes
} from './shape';
import type { VariantContainer, VariantShape } from './variant';
import type { Children } from '../utils/children';
import type { Uuid } from '../utils/uuid';

export type FrameShape = ShapeBaseAttributes &
  ShapeAttributes &
  ShapeGeomAttributes &
  FrameAttributes &
  LayoutAttributes &
  LayoutChildAttributes &
  VariantShape &
  VariantContainer &
  Children;

type FrameAttributes = {
  type: 'frame';
  shapes?: Uuid[];
  hideFillOnExport?: boolean;
  showContent?: boolean;
  hideInViewer?: boolean;
};
