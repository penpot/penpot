import { RendererConfig } from 'react-cosmos-core';

export type DomRendererConfig = RendererConfig & {
  containerQuerySelector?: null | string;
};
