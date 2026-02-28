import type { Uuid } from '../utils/uuid';

export type VariantProperty = {
  name: string;
  value: string;
};

export type VariantComponent = {
  variantId?: Uuid;
  variantProperties?: VariantProperty[];
};

export type VariantShape = {
  variantId?: Uuid;
  variantName?: string;
  variantError?: string;
};

export type VariantContainer = {
  isVariantContainer?: boolean;
};
