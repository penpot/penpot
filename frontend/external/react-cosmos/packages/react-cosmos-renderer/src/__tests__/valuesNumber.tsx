import retry from '@skidding/async-retry';
import React from 'react';
import { createValue, uuid } from 'react-cosmos-core';
import {
  ReactTestRenderer,
  ReactTestRendererJSON,
  act,
} from 'react-test-renderer';
import { useValue } from '../fixture/useValue/index.js';
import { testRenderer } from '../testHelpers/testRenderer.js';
import { wrapDefaultExport } from '../testHelpers/wrapDefaultExport.js';

function createFixtures({ defaultValue }: { defaultValue: number }) {
  const MyComponent = () => {
    const [count, setCount] = useValue('count', { defaultValue });
    return (
      <button onClick={() => setCount(prevCount => prevCount + 1)}>
        {count} clicks
      </button>
    );
  };
  return wrapDefaultExport({
    first: <MyComponent />,
  });
}

const rendererId = uuid();
const fixtures = createFixtures({ defaultValue: 0 });
const fixtureId = { path: 'first' };

testRenderer(
  'renders fixture',
  { rendererId, fixtures },
  async ({ renderer, selectFixture }) => {
    selectFixture({ rendererId, fixtureId, fixtureState: {} });
    await rendered(renderer, '0 clicks');
  }
);

testRenderer(
  'creates fixture state',
  { rendererId, fixtures },
  async ({ selectFixture, fixtureStateChange }) => {
    selectFixture({ rendererId, fixtureId, fixtureState: {} });
    await fixtureStateChange({
      rendererId,
      fixtureId,
      fixtureState: {
        props: expect.any(Array),
        controls: {
          count: {
            type: 'standard',
            defaultValue: createValue(0),
            currentValue: createValue(0),
          },
        },
      },
    });
  }
);

testRenderer(
  'updates fixture state via setter',
  { rendererId, fixtures },
  async ({ renderer, selectFixture, fixtureStateChange }) => {
    selectFixture({ rendererId, fixtureId, fixtureState: {} });
    await rendered(renderer, '0 clicks');
    clickButton(renderer);
    clickButton(renderer);
    await fixtureStateChange({
      rendererId,
      fixtureId,
      fixtureState: {
        props: expect.any(Array),
        controls: {
          count: {
            type: 'standard',
            defaultValue: createValue(0),
            currentValue: createValue(2),
          },
        },
      },
    });
  }
);

testRenderer(
  'resets fixture state on default value change',
  { rendererId, fixtures },
  async ({ renderer, update, selectFixture, fixtureStateChange }) => {
    selectFixture({ rendererId, fixtureId, fixtureState: {} });
    await rendered(renderer, '0 clicks');
    update({
      rendererId,
      fixtures: createFixtures({ defaultValue: 5 }),
    });
    await fixtureStateChange({
      rendererId,
      fixtureId,
      fixtureState: {
        props: expect.any(Array),
        controls: {
          count: {
            type: 'standard',
            defaultValue: createValue(5),
            currentValue: createValue(5),
          },
        },
      },
    });
  }
);

function getButtonText(renderer: ReactTestRenderer) {
  return getSingleRendererElement(renderer).children!.join('');
}

async function rendered(renderer: ReactTestRenderer, text: string) {
  await retry(() => expect(getButtonText(renderer)).toEqual(text));
}

function clickButton(renderer: ReactTestRenderer) {
  act(() => {
    getSingleRendererElement(renderer).props.onClick();
  });
}

function getSingleRendererElement(renderer: ReactTestRenderer) {
  return renderer.toJSON() as ReactTestRendererJSON;
}
