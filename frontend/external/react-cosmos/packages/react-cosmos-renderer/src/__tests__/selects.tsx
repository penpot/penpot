import retry from '@skidding/async-retry';
import React from 'react';
import { uuid } from 'react-cosmos-core';
import {
  act,
  ReactTestRenderer,
  ReactTestRendererJSON,
} from 'react-test-renderer';
import { useSelect } from '../fixture/useSelect/index.js';
import { testRenderer } from '../testHelpers/testRenderer.js';
import { wrapDefaultExport } from '../testHelpers/wrapDefaultExport.js';

type Option = 'first' | 'second' | 'third';

const options: Option[] = ['first', 'second', 'third'];

function createFixtures({ defaultValue }: { defaultValue: Option }) {
  const MyComponent = () => {
    const [value, setValue] = useSelect('selectName', {
      defaultValue,
      options,
    });
    return (
      <input
        type="text"
        value={value}
        onChange={e => setValue(e.target.value as Option)}
      />
    );
  };
  return wrapDefaultExport({
    first: <MyComponent />,
  });
}

const rendererId = uuid();
const fixtures = createFixtures({ defaultValue: 'first' });
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

testRenderer(
  'reflects fixture state change',
  { rendererId, fixtures },
  async ({ renderer, selectFixture, setFixtureState, getLastFixtureState }) => {
    selectFixture({ rendererId, fixtureId, fixtureState: {} });
    await rendered(renderer, 'first');
    const fixtureState = await getLastFixtureState();
    setFixtureState({
      rendererId,
      fixtureId,
      fixtureState: {
        ...setFixtureState,
        controls: {
          ...fixtureState.controls,
          selectName: {
            type: 'select',
            options: ['first', 'second', 'third'],
            defaultValue: 'first',
            currentValue: 'second',
          },
        },
      },
    });
    await rendered(renderer, 'second');
  }
);

testRenderer(
  'updates fixture state via setter',
  { rendererId, fixtures },
  async ({ renderer, selectFixture, fixtureStateChange }) => {
    selectFixture({ rendererId, fixtureId, fixtureState: {} });
    await rendered(renderer, 'first');
    changeValue(renderer, 'second');
    await rendered(renderer, 'second');
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
            currentValue: 'second',
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
    await rendered(renderer, 'first');
    update({
      rendererId,
      fixtures: createFixtures({ defaultValue: 'third' }),
    });
    await rendered(renderer, 'third');
    await fixtureStateChange({
      rendererId,
      fixtureId,
      fixtureState: {
        props: expect.any(Array),
        controls: {
          selectName: {
            type: 'select',
            options: ['first', 'second', 'third'],
            defaultValue: 'third',
            currentValue: 'third',
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

function changeValue(renderer: ReactTestRenderer, value: Option) {
  act(() => {
    getSingleRendererElement(renderer).props.onChange({ target: { value } });
  });
}

function getSingleRendererElement(renderer: ReactTestRenderer) {
  return renderer.toJSON() as ReactTestRendererJSON;
}
