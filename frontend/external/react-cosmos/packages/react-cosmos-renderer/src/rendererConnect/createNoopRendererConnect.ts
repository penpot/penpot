import { RendererConnect } from 'react-cosmos-core';

export function createNoopRendererConnect(): RendererConnect {
  return {
    postMessage() {},
    onMessage() {
      return () => {};
    },
  };
}
