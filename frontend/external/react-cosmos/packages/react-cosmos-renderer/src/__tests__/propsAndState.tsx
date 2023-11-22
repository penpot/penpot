import retry from '@skidding/async-retry';
import React from 'react';
import {
  createValues,
  updateFixtureStateClassState,
  updateFixtureStateProps,
  uuid,
} from 'react-cosmos-core';
import { SuffixCounter } from '../testHelpers/components.js';
import {
  anyClassState,
  anyProps,
  getProps,
} from '../testHelpers/fixtureState.js';
import { testRenderer } from '../testHelpers/testRenderer.js';
import { wrapDefaultExport } from '../testHelpers/wrapDefaultExport.js';

const rendererId = uuid();
const fixtures = wrapDefaultExport({
  first: (
    // The extra levels of nesting capture a complex case regarding deep
    // comparison of children nodes
    <>
      <>
        <SuffixCounter suffix="times" />
      </>
    </>
  ),
});
const fixtureId = { path: 'first' };

testRenderer(
  'keeps state when resetting props',
  { rendererId, fixtures },
  async ({ renderer, selectFixture, setFixtureState, getLastFixtureState }) => {
    selectFixture({ rendererId, fixtureId, fixtureState: {} });
    let fixtureState = await getLastFixtureState();
    const [{ elementId }] = getProps(fixtureState);
    fixtureState = {
      ...fixtureState,
      classState: updateFixtureStateClassState({
        fixtureState,
        elementId,
        values: createValues({ count: 5 }),
      }),
    };
    setFixtureState({ rendererId, fixtureId, fixtureState });
    await retry(() => expect(renderer.toJSON()).toBe('5 times'));
    setFixtureState({
      rendererId,
      fixtureId,
      fixtureState: {
        ...fixtureState,
        props: updateFixtureStateProps({
          fixtureState,
          elementId,
          values: createValues({ suffix: 'timez' }),
        }),
      },
    });
    await retry(() => expect(renderer.toJSON()).toBe('5 timez'));
  }
);

testRenderer(
  'keeps state when transitioning props',
  { rendererId, fixtures },
  async ({ renderer, selectFixture, setFixtureState, getLastFixtureState }) => {
    selectFixture({ rendererId, fixtureId, fixtureState: {} });
    let fixtureState = await getLastFixtureState();
    const [{ elementId }] = getProps(fixtureState);
    fixtureState = {
      ...fixtureState,
      classState: updateFixtureStateClassState({
        fixtureState,
        elementId,
        values: createValues({ count: 5 }),
      }),
    };
    setFixtureState({ rendererId, fixtureId, fixtureState });
    await retry(() => expect(renderer.toJSON()).toBe('5 times'));
    setFixtureState({
      rendererId,
      fixtureId,
      fixtureState: {
        ...fixtureState,
        props: updateFixtureStateProps({
          fixtureState,
          elementId,
          values: createValues({ suffix: 'timez' }),
        }),
      },
    });
    await retry(() => expect(renderer.toJSON()).toBe('5 timez'));
  }
);

testRenderer(
  'keeps props when changing state',
  { rendererId, fixtures },
  async ({ renderer, selectFixture, setFixtureState, getLastFixtureState }) => {
    selectFixture({ rendererId, fixtureId, fixtureState: {} });
    let fixtureState = await getLastFixtureState();
    const [{ elementId }] = getProps(fixtureState);
    fixtureState = {
      ...fixtureState,
      props: updateFixtureStateProps({
        fixtureState,
        elementId,
        values: createValues({ suffix: 'timez' }),
      }),
    };
    setFixtureState({ rendererId, fixtureId, fixtureState });
    await retry(() => expect(renderer.toJSON()).toBe('0 timez'));
    setFixtureState({
      rendererId,
      fixtureId,
      fixtureState: {
        ...fixtureState,
        classState: updateFixtureStateClassState({
          fixtureState,
          elementId,
          values: createValues({ count: 5 }),
        }),
      },
    });
    await retry(() => expect(renderer.toJSON()).toBe('5 timez'));
  }
);

testRenderer(
  'updates props on fixture change',
  { rendererId, fixtures },
  async ({ renderer, update, selectFixture, fixtureStateChange }) => {
    selectFixture({ rendererId, fixtureId, fixtureState: {} });
    update({
      rendererId,
      fixtures: wrapDefaultExport({
        first: <SuffixCounter suffix="timez" />,
      }),
    });
    await retry(() => expect(renderer.toJSON()).toBe('0 timez'));
    await fixtureStateChange({
      rendererId,
      fixtureId,
      fixtureState: {
        props: [
          anyProps({
            values: createValues({ suffix: 'timez' }),
          }),
        ],
        classState: [
          anyClassState({
            values: createValues({ count: 0 }),
          }),
        ],
      },
    });
  }
);
