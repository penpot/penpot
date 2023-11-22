import retry from '@skidding/async-retry';
import React from 'react';
import { uuid } from 'react-cosmos-core';
import { FixtureContext } from '../fixture/FixtureContext.js';
import { testRenderer } from '../testHelpers/testRenderer.js';
import { wrapDefaultExport } from '../testHelpers/wrapDefaultExport.js';

function FirstFixture() {
  const { fixtureState } = React.useContext(FixtureContext);
  return <>{fixtureState.custom ? 'FirstCustom' : 'First'}</>;
}

function SecondFixture() {
  const { fixtureState } = React.useContext(FixtureContext);
  return <>{fixtureState.custom ? 'SecondCustom' : 'Second'}</>;
}

const rendererId = uuid();
const fixtures = wrapDefaultExport({
  first: <FirstFixture />,
  second: <SecondFixture />,
});

testRenderer(
  'changes fixture',
  { rendererId, fixtures },
  async ({ renderer, selectFixture }) => {
    selectFixture({
      rendererId,
      fixtureId: { path: 'first' },
      fixtureState: {},
    });
    await retry(() => expect(renderer.toJSON()).toBe('First'));

    selectFixture({
      rendererId,
      fixtureId: { path: 'second' },
      fixtureState: {},
    });
    await retry(() => expect(renderer.toJSON()).toBe('Second'));
  }
);

testRenderer(
  'does not leak fixture state when resetting fixture',
  { rendererId, fixtures },
  async ({ renderer, selectFixture, setFixtureState }) => {
    selectFixture({
      rendererId,
      fixtureId: { path: 'first' },
      fixtureState: {},
    });
    await retry(() => expect(renderer.toJSON()).toBe('First'));

    setFixtureState({
      rendererId,
      fixtureId: { path: 'first' },
      fixtureState: {
        props: [],
        custom: true,
      },
    });
    await retry(() => expect(renderer.toJSON()).toBe('FirstCustom'));

    selectFixture({
      rendererId,
      fixtureId: { path: 'first' },
      fixtureState: {},
    });
    await retry(() => expect(renderer.toJSON()).toBe('First'));
  }
);

testRenderer(
  'does not leak fixture state from one fixture to another',
  { rendererId, fixtures },
  async ({ renderer, selectFixture, setFixtureState }) => {
    selectFixture({
      rendererId,
      fixtureId: { path: 'first' },
      fixtureState: {},
    });
    await retry(() => expect(renderer.toJSON()).toBe('First'));

    setFixtureState({
      rendererId,
      fixtureId: { path: 'first' },
      fixtureState: {
        props: [],
        custom: true,
      },
    });
    await retry(() => expect(renderer.toJSON()).toBe('FirstCustom'));

    selectFixture({
      rendererId,
      fixtureId: { path: 'second' },
      fixtureState: {},
    });
    await retry(() => expect(renderer.toJSON()).toBe('Second'));
  }
);
