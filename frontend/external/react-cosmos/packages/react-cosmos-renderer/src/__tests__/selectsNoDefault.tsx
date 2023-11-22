import retry from '@skidding/async-retry';
import React from 'react';
import { uuid } from 'react-cosmos-core';
import { ReactTestRenderer, ReactTestRendererJSON } from 'react-test-renderer';
import { useSelect } from '../fixture/useSelect/index.js';
import { testRenderer } from '../testHelpers/testRenderer.js';
import { wrapDefaultExport } from '../testHelpers/wrapDefaultExport.js';

function createFixtures() {
  const MyComponent = () => {
    const [value, setValue] = useSelect('selectName', {
      options: ['first', 'second', 'third'],
    });
    return (
      <input
        type="text"
        value={value}
        onChange={e => setValue(e.target.value as typeof value)}
      />
    );
  };
  return wrapDefaultExport({
    first: <MyComponent />,
  });
}

const rendererId = uuid();
const fixtures = createFixtures();
const fixtureId = { path: 'first' };

testRenderer(
  'renders fixture',
  { rendererId, fixtures },
  async ({ renderer, selectFixture }) => {
    selectFixture({ rendererId, fixtureId, fixtureState: {} });
    await rendered(renderer, 'first');
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
          selectName: {
            type: 'select',
            options: ['first', 'second', 'third'],
            defaultValue: 'first',
            currentValue: 'first',
          },
        },
      },
    });
  }
);

async function rendered(renderer: ReactTestRenderer, text: string) {
  await retry(() =>
    expect(getSingleRendererElement(renderer).props.value).toEqual(text)
  );
}

function getSingleRendererElement(renderer: ReactTestRenderer) {
  return renderer.toJSON() as ReactTestRendererJSON;
}
