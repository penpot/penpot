import type { Uuid } from './uuid';

export type Blur = {
  id?: Uuid;
  type: 'layer-blur';
  value: number;
  hidden: boolean;
};
