import type { LayoutAttributes, LayoutChildAttributes } from './lib/types/shapes/layout';
import type {
  ShapeAttributes,
  ShapeBaseAttributes,
  ShapeGeomAttributes
} from './lib/types/shapes/shape';
import type { Children } from './lib/types/utils/children';
import type { Uuid } from './lib/types/utils/uuid';

export type ComponentRoot = {
  name: string;
  componentId: Uuid;
  frameId: Uuid;
  variantId?: Uuid;
};

export type ComponentTextPropertyOverride = {
  id: string;
  type: 'TEXT';
  value: string;
  defaultValue: string;
};

export type ComponentInstance = ShapeBaseAttributes &
  ShapeAttributes &
  ShapeGeomAttributes &
  LayoutAttributes &
  LayoutChildAttributes &
  Children & {
    componentRoot: boolean;
    showContent?: boolean;
    isOrphan: boolean;
    type: 'instance';
  };

export type ComponentProperty = {
  type: 'BOOLEAN' | 'TEXT' | 'INSTANCE_SWAP' | 'VARIANT';
  defaultValue: string | boolean;
  preferredValues?: {
    type: 'COMPONENT' | 'COMPONENT_SET';
    key: string;
  }[];
  variantOptions?: string[];
};

// This type comes directly from Figma. We have it here because we need to reference it from the UI
export type ComponentPropertyReference =
  | {
      [nodeProperty in 'visible' | 'characters' | 'mainComponent']?: string;
    }
  | null;
