'use client';
import React from 'react';
import { FixtureId, FixtureState, RendererConnect } from 'react-cosmos-core';
import { RendererProvider } from './RendererProvider.js';

type Props = {
  children: React.ReactNode;
  rendererId: string;
  rendererConnect: RendererConnect;
  locked: boolean;
  selectedFixtureId: FixtureId | null;
  reloadRenderer(): void;
};
export function StatefulRendererProvider({
  children,
  selectedFixtureId,
  ...otherProps
}: Props) {
  const [selectedFixture, setSelectedFixture] = React.useState(
    () =>
      selectedFixtureId && {
        fixtureId: selectedFixtureId,
        initialFixtureState: {},
        renderKey: 0,
      }
  );

  const selectFixture = React.useCallback(
    (fixtureId: FixtureId, initialFixtureState: FixtureState) => {
      setSelectedFixture(prevState => ({
        fixtureId,
        initialFixtureState,
        renderKey: (prevState?.renderKey ?? 0) + 1,
      }));
    },
    []
  );

  const unselectFixture = React.useCallback(() => {
    setSelectedFixture(null);
  }, []);

  return (
    <RendererProvider
      {...otherProps}
      selectedFixture={selectedFixture}
      selectFixture={selectFixture}
      unselectFixture={unselectFixture}
    >
      {children}
    </RendererProvider>
  );
}
