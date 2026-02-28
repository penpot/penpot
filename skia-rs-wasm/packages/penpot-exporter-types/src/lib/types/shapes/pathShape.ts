import type { LayoutChildAttributes } from './layout';
import type { ShapeAttributes, ShapeBaseAttributes } from './shape';

export type PathShape = ShapeBaseAttributes &
  ShapeAttributes &
  PathAttributes &
  LayoutChildAttributes;

export type PathAttributes = {
  type: 'path';
  content: string;
  svgAttrs?: {
    fillRule?: FillRules;
  };
};

export type FillRules = 'evenodd' | 'nonzero';
