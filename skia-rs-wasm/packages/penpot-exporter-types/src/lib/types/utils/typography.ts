import type { TextTypography } from '../shapes/textShape';
import type { Uuid } from './uuid';

export type Typography = TextTypography & {
  id?: Uuid;
  name?: string;
  path?: string;
};
