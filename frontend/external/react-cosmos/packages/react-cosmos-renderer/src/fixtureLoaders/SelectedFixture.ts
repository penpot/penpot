import { FixtureId, FixtureState } from 'react-cosmos-core';

export type SelectedFixture = {
  fixtureId: FixtureId;
  initialFixtureState: FixtureState;
  renderKey: number;
};
