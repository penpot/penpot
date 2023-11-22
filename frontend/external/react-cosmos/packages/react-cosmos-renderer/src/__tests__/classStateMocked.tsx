import retry from '@skidding/async-retry';
import React from 'react';
import {
  ClassStateMock,
  createValues,
  removeFixtureStateClassState,
  updateFixtureStateClassState,
  uuid,
} from 'react-cosmos-core';
import { CoolCounter, Counter } from '../testHelpers/components.js';
import {
  anyClassState,
  anyProps,
  getClassState,
} from '../testHelpers/fixtureState.js';
import { testRenderer } from '../testHelpers/testRenderer.js';
import { wrapActSetTimeout } from '../testHelpers/wrapActSetTimeout.js';
import { wrapDefaultExport } from '../testHelpers/wrapDefaultExport.js';

beforeAll(wrapActSetTimeout);

const rendererId = uuid();
const fixtures = wrapDefaultExport({
  first: (
    <ClassStateMock state={{ count: 5 }}>
      <Counter />
    </ClassStateMock>
  ),
});
const fixtureId = { path: 'first' };

testRenderer(
  'captures mocked state',
  { rendererId, fixtures },
  async ({ renderer, selectFixture, fixtureStateChange }) => {
    selectFixture({ rendererId, fixtureId, fixtureState: {} });
    await retry(() => expect(renderer.toJSON()).toBe('5 times'));
    await fixtureStateChange({
      rendererId,
      fixtureId,
      fixtureState: {
        props: [anyProps()],
        classState: [
          anyClassState({
            values: createValues({ count: 5 }),
            componentName: 'Counter',
          }),
        ],
      },
    });
  }
);

testRenderer(
  'overwrites mocked state',
  { rendererId, fixtures },
  async ({ renderer, selectFixture, setFixtureState, getLastFixtureState }) => {
    selectFixture({ rendererId, fixtureId, fixtureState: {} });
    const fixtureState = await getLastFixtureState();
    const [{ elementId }] = getClassState(fixtureState);
    setFixtureState({
      rendererId,
      fixtureId,
      fixtureState: {
        classState: updateFixtureStateClassState({
          fixtureState,
          elementId,
          values: createValues({ count: 100 }),
        }),
      },
    });
    await retry(() => expect(renderer.toJSON()).toBe('100 times'));
    // A second update will provide code coverage for a different branch:
    // the transition between fixture state values
    setFixtureState({
      rendererId,
      fixtureId,
      fixtureState: {
        classState: updateFixtureStateClassState({
          fixtureState,
          elementId,
          values: createValues({ count: 200 }),
        }),
      },
    });
    await retry(() => expect(renderer.toJSON()).toBe('200 times'));
  }
);

testRenderer(
  'removes mocked state property',
  { rendererId, fixtures },
  async ({ renderer, selectFixture, setFixtureState, getLastFixtureState }) => {
    selectFixture({ rendererId, fixtureId, fixtureState: {} });
    const fixtureState = await getLastFixtureState();
    const [{ elementId }] = getClassState(fixtureState);
    setFixtureState({
      rendererId,
      fixtureId,
      fixtureState: {
        classState: updateFixtureStateClassState({
          fixtureState,
          elementId,
          values: {},
        }),
      },
    });
    await retry(() => expect(renderer.toJSON()).toBe('Missing count'));
  }
);

testRenderer(
  'reverts to mocked state',
  { rendererId, fixtures },
  async ({
    renderer,
    selectFixture,
    setFixtureState,
    fixtureStateChange,
    getLastFixtureState,
  }) => {
    selectFixture({ rendererId, fixtureId, fixtureState: {} });
    const fixtureState = await getLastFixtureState();
    const [{ elementId }] = getClassState(fixtureState);
    setFixtureState({
      rendererId,
      fixtureId,
      fixtureState: {
        classState: updateFixtureStateClassState({
          fixtureState,
          elementId,
          values: createValues({ count: 10 }),
        }),
      },
    });
    await retry(() => expect(renderer.toJSON()).toBe('10 times'));
    setFixtureState({
      rendererId,
      fixtureId,
      fixtureState: {
        classState: removeFixtureStateClassState(fixtureState, elementId),
      },
    });
    await retry(() => expect(renderer.toJSON()).toBe('5 times'));
    // After the state is removed from the fixture state, the original
    // state is added back through a fixtureStateChange message
    await fixtureStateChange({
      rendererId,
      fixtureId,
      fixtureState: {
        props: [anyProps()],
        classState: [
          anyClassState({
            values: createValues({ count: 5 }),
            componentName: 'Counter',
          }),
        ],
      },
    });
  }
);

testRenderer(
  'applies fixture state to replaced component type',
  { rendererId, fixtures },
  async ({
    renderer,
    update,
    selectFixture,
    setFixtureState,
    getLastFixtureState,
  }) => {
    selectFixture({ rendererId, fixtureId, fixtureState: {} });
    const fixtureState = await getLastFixtureState();
    const [{ elementId }] = getClassState(fixtureState);
    setFixtureState({
      rendererId,
      fixtureId,
      fixtureState: {
        classState: updateFixtureStateClassState({
          fixtureState,
          elementId,
          values: createValues({ count: 50 }),
        }),
      },
    });
    await retry(() => expect(renderer.toJSON()).toBe('50 times'));
    update({
      rendererId,
      fixtures: wrapDefaultExport({
        first: (
          <ClassStateMock state={{ count: 5 }}>
            <CoolCounter />
          </ClassStateMock>
        ),
      }),
    });
    await retry(() => expect(renderer.toJSON()).toBe('50 timez'));
  }
);

testRenderer(
  'overwrites fixture state on fixture change',
  { rendererId, fixtures },
  async ({
    renderer,
    update,
    selectFixture,
    setFixtureState,
    fixtureStateChange,
    getLastFixtureState,
  }) => {
    selectFixture({ rendererId, fixtureId, fixtureState: {} });
    const fixtureState = await getLastFixtureState();
    const [{ elementId }] = getClassState(fixtureState);
    setFixtureState({
      rendererId,
      fixtureId,
      fixtureState: {
        classState: updateFixtureStateClassState({
          fixtureState,
          elementId,
          values: createValues({ count: 6 }),
        }),
      },
    });
    await retry(() => expect(renderer.toJSON()).toBe('6 times'));
    // When the fixture changes the fixture state follows along
    update({
      rendererId,
      fixtures: wrapDefaultExport({
        first: (
          <ClassStateMock state={{ count: 50 }}>
            <Counter />
          </ClassStateMock>
        ),
      }),
    });
    await fixtureStateChange({
      rendererId,
      fixtureId,
      fixtureState: {
        props: [anyProps()],
        classState: [
          anyClassState({
            values: createValues({ count: 50 }),
            componentName: 'Counter',
          }),
        ],
      },
    });
    expect(renderer.toJSON()).toBe('50 times');
  }
);

testRenderer(
  'clears fixture state for removed fixture element',
  { rendererId, fixtures },
  async ({ renderer, update, selectFixture, fixtureStateChange }) => {
    selectFixture({ rendererId, fixtureId, fixtureState: {} });
    await fixtureStateChange({
      rendererId,
      fixtureId,
      fixtureState: {
        props: [anyProps()],
        classState: [
          anyClassState({
            values: createValues({ count: 5 }),
            componentName: 'Counter',
          }),
        ],
      },
    });
    update({
      rendererId,
      fixtures: wrapDefaultExport({
        // Counter element from fixture is gone, and so should the
        // fixture state related to it.
        first: 'No counts for you.',
      }),
    });
    await retry(() => expect(renderer.toJSON()).toBe('No counts for you.'));
    await fixtureStateChange({
      rendererId,
      fixtureId,
      fixtureState: {
        props: [],
        classState: [],
      },
    });
  }
);
