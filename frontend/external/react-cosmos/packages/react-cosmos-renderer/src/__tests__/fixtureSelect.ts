import retry from '@skidding/async-retry';
import { uuid } from 'react-cosmos-core';
import { testRenderer } from '../testHelpers/testRenderer.js';
import { wrapActSetTimeout } from '../testHelpers/wrapActSetTimeout.js';
import { wrapDefaultExport } from '../testHelpers/wrapDefaultExport.js';

beforeAll(wrapActSetTimeout);

const rendererId = uuid();
const fixtures = wrapDefaultExport({
  first: { one: 'First' },
  second: 'Second',
});

testRenderer(
  'renders selected fixture',
  { rendererId, fixtures },
  async ({ renderer, selectFixture }) => {
    selectFixture({
      rendererId,
      fixtureId: { path: 'second' },
      fixtureState: {},
    });
    await retry(() => expect(renderer.toJSON()).toBe('Second'));
  }
);

testRenderer(
  'renders selected named fixture',
  { rendererId, fixtures },
  async ({ renderer, selectFixture }) => {
    selectFixture({
      rendererId,
      fixtureId: { path: 'first', name: 'one' },
      fixtureState: {},
    });
    await retry(() => expect(renderer.toJSON()).toBe('First'));
  }
);

testRenderer(
  'renders first named fixture',
  { rendererId, fixtures },
  async ({ renderer, selectFixture }) => {
    selectFixture({
      rendererId,
      fixtureId: { path: 'first' },
      fixtureState: {},
    });
    await retry(() => expect(renderer.toJSON()).toBe('First'));
  }
);

testRenderer(
  'creates fixture state',
  { rendererId, fixtures },
  async ({ selectFixture, fixtureStateChange }) => {
    selectFixture({
      rendererId,
      fixtureId: { path: 'second' },
      fixtureState: {},
    });
    await fixtureStateChange({
      rendererId,
      fixtureId: { path: 'second' },
      fixtureState: {
        props: [],
      },
    });
  }
);

testRenderer(
  'renders blank state after unselecting fixture',
  { rendererId, fixtures },
  async ({ renderer, selectFixture, unselectFixture }) => {
    selectFixture({
      rendererId,
      fixtureId: { path: 'first', name: 'one' },
      fixtureState: {},
    });
    await retry(() => expect(renderer.toJSON()).toBe('First'));
    unselectFixture({ rendererId });
    await retry(() => expect(renderer.toJSON()).toBe('No fixture selected.'));
  }
);

testRenderer(
  'ignores "selectFixture" message for different renderer',
  { rendererId, fixtures },
  async ({ renderer, selectFixture }) => {
    selectFixture({
      rendererId: 'foobar',
      fixtureId: { path: 'second' },
      fixtureState: {},
    });
    await retry(() => expect(renderer.toJSON()).toBe('No fixture selected.'));
  }
);

testRenderer(
  'renders missing state on unknown fixture path',
  { rendererId, fixtures },
  async ({ renderer, selectFixture }) => {
    selectFixture({
      rendererId,
      fixtureId: { path: 'third' },
      fixtureState: {},
    });
    await retry(() =>
      expect(renderer.toJSON()).toBe('Fixture path not found: third')
    );
  }
);
