import type { LayoutAttributes, LayoutChildAttributes } from './layout';
import type {
  ShapeAttributes,
  ShapeBaseAttributes,
  ShapeGeomAttributes
} from './shape';
import type { VariantComponent, VariantProperty, VariantShape } from './variant';
import type { Children } from '../utils/children';
import type { Uuid } from '../utils/uuid';

export type ComponentShape = ShapeBaseAttributes &
  ShapeAttributes &
  ShapeGeomAttributes &
  ComponentAttributes &
  LayoutAttributes &
  LayoutChildAttributes &
  VariantShape &
  VariantComponent &
  Children;

type ComponentAttributes = {
  type: 'component';
  path: string;
  showContent?: boolean;
  mainInstanceId?: Uuid;
  mainInstancePage?: Uuid;
};

export type PenpotComponent = {
  componentId: Uuid;
  fileId?: Uuid;
  name?: string;
  path?: string;
  frameId?: Uuid;
  pageId?: Uuid;
  variantId?: Uuid;
  variantProperties?: VariantProperty[];
};
