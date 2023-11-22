import React from 'react';
import { UserModuleWrappers } from 'react-cosmos-core';
import { createRoot } from 'react-dom/client';
import { DomFixtureLoader } from './DomFixtureLoader.js';
import { DomRendererConfig } from './DomRendererConfig.js';
import { getDomContainer } from './getDomContainer.js';

type CachedRoot = {
  domContainer: Element;
  reactRoot: ReturnType<typeof createRoot>;
};
let cachedRoot: CachedRoot | null = null;

type Args = {
  rendererConfig: DomRendererConfig;
  moduleWrappers: UserModuleWrappers;
};
export function mountDomRenderer({ rendererConfig, moduleWrappers }: Args) {
  const domContainer = getDomContainer(rendererConfig.containerQuerySelector);
  if (!cachedRoot || cachedRoot.domContainer !== domContainer) {
    const reactRoot = createRoot(domContainer);
    cachedRoot = { domContainer, reactRoot };
  }

  cachedRoot.reactRoot.render(
    <DomFixtureLoader
      rendererConfig={rendererConfig}
      moduleWrappers={moduleWrappers}
    />
  );
}
