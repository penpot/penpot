import type { Color } from './color';

import type { Gradient } from './gradient';
import type { ImageColor, PartialImageColor } from './imageColor';
import type { Uuid } from './uuid';

export type Fill = FigmaFill | PenpotFill;

// @TODO: move to any other place
type FigmaFill = {
  fillColor?: string;
  fillOpacity?: number;
  fillColorGradient?: Gradient;
  fillColorRefFile?: Uuid;
  fillColorRefId?: Uuid;
  fillImage?: PartialImageColor;
};

type PenpotFill = {
  fillColor?: string;
  fillOpacity?: number;
  fillColorGradient?: Gradient;
  fillColorRefFile?: Uuid;
  fillColorRefId?: Uuid;
  fillImage?: ImageColor;
};

export type FillStyle = {
  name: string;
  fills: Fill[];
  colors: Color[];
};
