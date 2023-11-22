import React from 'react';
import {
  FixtureId,
  FixtureList,
  FixtureState,
  RendererConnect,
} from 'react-cosmos-core';
import { SelectedFixture } from '../fixtureLoaders/SelectedFixture.js';

export type RendererContextValue = {
  rendererId: string;
  rendererConnect: RendererConnect;
  locked: boolean;
  selectedFixture: SelectedFixture | null;
  selectFixture(fixtureId: FixtureId, initialFixtureState: FixtureState): void;
  unselectFixture(): void;
  reloadRenderer(): void;
  lazyItems: FixtureList;
  setLazyItems(nextItems: FixtureList): void;
};

export const RendererContext = React.createContext<RendererContextValue>({
  rendererId: 'default-renderer-id',
  rendererConnect: {
    postMessage: () => {},
    onMessage: () => () => {},
  },
  locked: false,
  selectedFixture: null,
  selectFixture: () => {},
  unselectFixture: () => {},
  reloadRenderer: () => {},
  lazyItems: {},
  setLazyItems: () => {},
});
