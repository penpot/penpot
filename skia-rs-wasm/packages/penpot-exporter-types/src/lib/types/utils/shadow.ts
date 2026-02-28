import type { Color } from './color';
import type { Uuid } from './uuid';

export type ShadowStyle = 'drop-shadow' | 'inner-shadow';

export type Shadow = {
  id: Uuid | null;
  style: ShadowStyle;
  offsetX: number;
  offsetY: number;
  blur: number;
  spread: number;
  hidden: boolean;
  color: Color;
};
