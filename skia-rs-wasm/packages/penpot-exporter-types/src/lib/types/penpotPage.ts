import type { Children } from './utils/children';
import type { Uuid } from './utils/uuid';

export type PenpotPage = {
  id?: Uuid;
  name: string;
  background?: string;
} & Children;
