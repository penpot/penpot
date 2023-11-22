import retry from '@skidding/async-retry';
import React from 'react';
import { createValues, updateFixtureStateProps, uuid } from 'react-cosmos-core';
import { getProps } from '../testHelpers/fixtureState.js';
import { testRenderer } from '../testHelpers/testRenderer.js';
import { wrapDefaultExport } from '../testHelpers/wrapDefaultExport.js';

const rendererId = uuid();
const fixtures = createFixtures();
const fixtureId = { path: 'first' };

function createFixtures() {
  function HelloMessage({ name }: { name: string }) {
    return <>{`Hello ${name}`}</>;
  }
  return wrapDefaultExport({
    first: <HelloMessage name="Theo" />,
  });
}

testRenderer(
  'persists props after type changes reference but keeps name (hmr simulation)',
  { rendererId, fixtures },
  async ({
    renderer,
    update,
    selectFixture,
    getLastFixtureState,
    setFixtureState,
  }) => {
    selectFixture({ rendererId, fixtureId, fixtureState: {} });
    await retry(() => expect(renderer.toJSON()).toBe('Hello Theo'));
    const fixtureState = await getLastFixtureState();
    const [{ elementId }] = getProps(fixtureState);
    setFixtureState({
      rendererId,
      fixtureId,
      fixtureState: {
        props: updateFixtureStateProps({
          fixtureState,
          elementId,
          values: createValues({ name: 'Theo Von' }),
        }),
      },
    });
    await retry(() => expect(renderer.toJSON()).toBe('Hello Theo Von'));
    update({ rendererId, fixtures: createFixtures() });
    await retry(() => expect(renderer.toJSON()).toBe('Hello Theo Von'));
  }
);
