import React from 'react';
import { FixtureState, SetFixtureState } from 'react-cosmos-core';

export type FixtureContextValue = {
  fixtureState: FixtureState;
  setFixtureState: SetFixtureState;
};

export const FixtureContext = React.createContext<FixtureContextValue>({
  fixtureState: {},
  setFixtureState: () => {},
});
