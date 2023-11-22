'use client';
import React from 'react';
import { FixtureContext } from './FixtureContext.js';

type Props = {
  children: React.ReactNode;
  width: number;
  height: number;
};

export function Viewport({ children, width, height }: Props) {
  const { setFixtureState } = React.useContext(FixtureContext);

  React.useEffect(() => {
    setFixtureState(fixtureState => ({
      ...fixtureState,
      viewport: { width, height },
    }));
  }, [setFixtureState, width, height]);

  // https://github.com/DefinitelyTyped/DefinitelyTyped/issues/18051
  return <>{children}</>;
}

Viewport.cosmosCapture = false;
