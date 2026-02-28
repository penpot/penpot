import type { Uuid } from './utils/uuid';

export type PenpotFile = {
  id?: Uuid;
  name: string;
  isShared?: boolean;
  width?: number;
  height?: number;
};
