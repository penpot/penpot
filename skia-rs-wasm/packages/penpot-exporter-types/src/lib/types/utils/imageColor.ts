import type { Uuid } from './uuid';

export type ImageColor = {
  name?: string;
  width: number;
  height: number;
  mtype?: string;
  id?: Uuid;
  keepAspectRatio?: boolean;
  dataUri?: string;
};

// @TODO: move to any other place
export type PartialImageColor = {
  imageHash: string;
};
