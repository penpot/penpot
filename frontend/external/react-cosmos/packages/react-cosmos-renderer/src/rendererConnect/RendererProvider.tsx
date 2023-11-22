'use client';
import React from 'react';
import {
  FixtureId,
  FixtureList,
  FixtureState,
  RendererConnect,
} from 'react-cosmos-core';
import { SelectedFixture } from '../fixtureLoaders/SelectedFixture.js';
import { RendererContext } from './RendererContext.js';

type Props = {
  children: React.ReactNode;
  rendererId: string;
  rendererConnect: RendererConnect;
  locked: boolean;
  selectedFixture: SelectedFixture | null;
  selectFixture(fixtureId: FixtureId, initialFixtureState: FixtureState): void;
  unselectFixture(): void;
  reloadRenderer(): void;
};
export function RendererProvider(props: Props) {
  const [lazyItems, setLazyItems] = React.useState<FixtureList>({});

  const value = React.useMemo(() => {
    return {
      rendererId: props.rendererId,
      rendererConnect: props.rendererConnect,
      locked: props.locked,
      selectedFixture: props.selectedFixture,
      selectFixture: props.selectFixture,
      unselectFixture: props.unselectFixture,
      reloadRenderer: props.reloadRenderer,
      lazyItems,
      setLazyItems,
    };
  }, [
    lazyItems,
    props.locked,
    props.reloadRenderer,
    props.rendererConnect,
    props.rendererId,
    props.selectFixture,
    props.selectedFixture,
    props.unselectFixture,
  ]);

  return (
    <RendererContext.Provider value={value}>
      {props.children}
    </RendererContext.Provider>
  );
}
