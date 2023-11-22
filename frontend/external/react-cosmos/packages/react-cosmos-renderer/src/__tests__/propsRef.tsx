import retry from '@skidding/async-retry';
import { uniq } from 'lodash-es';
import React from 'react';
import {
  createValues,
  resetFixtureStateProps,
  updateFixtureStateProps,
  uuid,
} from 'react-cosmos-core';
import { HelloMessageCls } from '../testHelpers/components.js';
import { getProps } from '../testHelpers/fixtureState.js';
import { testRenderer } from '../testHelpers/testRenderer.js';
import { wrapDefaultExport } from '../testHelpers/wrapDefaultExport.js';

const rendererId = uuid();
const fixtureId = { path: 'first' };

let refs: React.Component[] = [];
beforeEach(() => {
  refs = [];
});

// Intentionally create new ref function on every update to get the ref
// to be called more than once even if the component instance is reused
const getFixtures = () =>
  wrapDefaultExport({
    first: (
      <HelloMessageCls
        ref={elRef => {
          if (elRef) {
            refs.push(elRef);
          }
        }}
        name="Blanca"
      />
    ),
  });

testRenderer(
  'transitions props (reuses component instance)',
  { rendererId, fixtures: getFixtures() },
  async ({
    renderer,
    update,
    selectFixture,
    setFixtureState,
    getLastFixtureState,
  }) => {
    selectFixture({ rendererId, fixtureId, fixtureState: {} });
    const fixtureState = await getLastFixtureState();
    const [{ elementId }] = getProps(fixtureState);
    setFixtureState({
      rendererId,
      fixtureId,
      fixtureState: {
        props: updateFixtureStateProps({
          fixtureState,
          elementId,
          values: createValues({ name: 'B' }),
        }),
      },
    });
    await retry(() => expect(renderer.toJSON()).toEqual('Hello B'));
    update({ rendererId, fixtures: getFixtures() });
    await retry(() => {
      expect(renderer.toJSON()).toEqual('Hello Blanca');
      expect(uniq(refs).length).toBe(1);
    });
  }
);

testRenderer(
  'resets props (creates new component instance)',
  { rendererId, fixtures: getFixtures() },
  async ({
    renderer,
    update,
    selectFixture,
    setFixtureState,
    getLastFixtureState,
  }) => {
    selectFixture({ rendererId, fixtureId, fixtureState: {} });
    const fixtureState = await getLastFixtureState();
    const [{ elementId }] = getProps(fixtureState);
    setFixtureState({
      rendererId,
      fixtureId,
      fixtureState: {
        props: resetFixtureStateProps({
          fixtureState,
          elementId,
          values: createValues({ name: 'B' }),
        }),
      },
    });
    await retry(() => expect(renderer.toJSON()).toEqual('Hello B'));
    update({ rendererId, fixtures: getFixtures() });
    await retry(() => {
      expect(renderer.toJSON()).toEqual('Hello Blanca');
      expect(uniq(refs).length).toBe(2);
    });
  }
);
