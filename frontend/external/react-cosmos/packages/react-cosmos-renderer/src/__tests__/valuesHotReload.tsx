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

type CreateFixtureArgs = {
  countName?: string;
  toggledName?: string;
  defaultCount?: number;
  defaultToggled?: boolean;
};

function createFixtures({
  countName = 'count',
  toggledName = 'toggled',
  defaultCount = 0,
  defaultToggled = false,
}: CreateFixtureArgs = {}) {
  const MyComponent = () => {
    const [count, setCount] = useValue(countName, {
      defaultValue: defaultCount,
    });
    const [toggled, setToggled] = useValue(toggledName, {
      defaultValue: defaultToggled,
    });
    return (
      <>
        <button onClick={() => setCount(prevCount => prevCount + 1)}>
          {count}
        </button>
        <button onClick={() => setToggled(!toggled)}>{String(toggled)}</button>
      </>
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
  'preserves fixture state change (via setter) on default value change',
  { rendererId, fixtures },
  async ({ renderer, update, selectFixture, fixtureStateChange }) => {
    selectFixture({ rendererId, fixtureId, fixtureState: {} });
    await rendered(renderer, { countText: '0', toggledText: 'false' });
    clickCountButton(renderer);
    clickToggledButton(renderer);
    await rendered(renderer, { countText: '1', toggledText: 'true' });
    update({
      rendererId,
      fixtures: createFixtures({ defaultCount: 2, defaultToggled: false }),
    });
    await fixtureStateChange({
      rendererId,
      fixtureId,
      fixtureState: {
        props: expect.any(Array),
        controls: {
          // `count` was reset, `toggled` was preserved
          count: {
            type: 'standard',
            defaultValue: createValue(2),
            currentValue: createValue(2),
          },
          toggled: {
            type: 'standard',
            defaultValue: createValue(false),
            currentValue: createValue(true),
          },
        },
      },
    });
  }
);

testRenderer(
  'preserves fixture state change (via setFixtureState) on default value change',
  { rendererId, fixtures },
  async ({
    renderer,
    update,
    selectFixture,
    setFixtureState,
    getLastFixtureState,
    fixtureStateChange,
  }) => {
    selectFixture({ rendererId, fixtureId, fixtureState: {} });
    await rendered(renderer, { countText: '0', toggledText: 'false' });
    const fixtureState = await getLastFixtureState();
    setFixtureState({
      rendererId,
      fixtureId,
      fixtureState: {
        ...setFixtureState,
        controls: {
          ...fixtureState.controls,
          count: {
            type: 'standard',
            defaultValue: createValue(0),
            currentValue: createValue(1),
          },
        },
      },
    });
    await rendered(renderer, { countText: '1', toggledText: 'false' });
    update({
      rendererId,
      fixtures: createFixtures({ defaultCount: 0, defaultToggled: true }),
    });
    await fixtureStateChange({
      rendererId,
      fixtureId,
      fixtureState: {
        props: expect.any(Array),
        controls: {
          // `count` was preserved, `toggled` was reset
          count: {
            type: 'standard',
            defaultValue: createValue(0),
            currentValue: createValue(1),
          },
          toggled: {
            type: 'standard',
            defaultValue: createValue(true),
            currentValue: createValue(true),
          },
        },
      },
    });
  }
);

testRenderer(
  'cleans up fixture state on input rename',
  { rendererId, fixtures },
  async ({ renderer, update, selectFixture, fixtureStateChange }) => {
    selectFixture({ rendererId, fixtureId, fixtureState: {} });
    await rendered(renderer, { countText: '0', toggledText: 'false' });
    clickCountButton(renderer);
    await fixtureStateChange({
      rendererId,
      fixtureId,
      fixtureState: {
        props: expect.any(Array),
        controls: {
          count: {
            type: 'standard',
            defaultValue: createValue(0),
            currentValue: createValue(1),
          },
          toggled: {
            type: 'standard',
            defaultValue: createValue(false),
            currentValue: createValue(false),
          },
        },
      },
    });
    update({
      rendererId,
      fixtures: createFixtures({
        toggledName: 'confirmed',
        defaultToggled: true,
      }),
    });
    await rendered(renderer, { countText: '1', toggledText: 'true' });
    await fixtureStateChange({
      rendererId,
      fixtureId,
      fixtureState: {
        props: expect.any(Array),
        controls: {
          count: {
            type: 'standard',
            defaultValue: createValue(0),
            currentValue: createValue(1),
          },
          confirmed: {
            type: 'standard',
            defaultValue: createValue(true),
            currentValue: createValue(true),
          },
          // KNOWN LIMITATION: `toggled` is still present in the fixture
          // state until the user resets the fixture state.
          toggled: {
            type: 'standard',
            defaultValue: createValue(false),
            currentValue: createValue(false),
          },
        },
      },
    });
  }
);

async function rendered(
  renderer: ReactTestRenderer,
  { countText, toggledText }: { countText: string; toggledText: string }
) {
  await retry(() =>
    expect(getButtonText(getCountButton(renderer))).toEqual(countText)
  );
  await retry(() =>
    expect(getButtonText(getToggledButton(renderer))).toEqual(toggledText)
  );
}

function clickCountButton(renderer: ReactTestRenderer) {
  toggleButton(getCountButton(renderer));
}

function clickToggledButton(renderer: ReactTestRenderer) {
  toggleButton(getToggledButton(renderer));
}

function getCountButton(renderer: ReactTestRenderer) {
  return (renderer.toJSON() as any)[0] as ReactTestRendererJSON;
}

function getToggledButton(renderer: ReactTestRenderer) {
  return (renderer.toJSON() as any)[1] as ReactTestRendererJSON;
}

function getButtonText(rendererNode: ReactTestRendererJSON) {
  return rendererNode.children!.join('');
}

function toggleButton(rendererNode: ReactTestRendererJSON) {
  act(() => {
    rendererNode.props.onClick();
  });
}
