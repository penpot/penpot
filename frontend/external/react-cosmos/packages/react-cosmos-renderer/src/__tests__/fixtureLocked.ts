import { setTimeout } from 'node:timers/promises';
import { uuid } from 'react-cosmos-core';
import { testRenderer } from '../testHelpers/testRenderer.js';
import { wrapDefaultExport } from '../testHelpers/wrapDefaultExport.js';

const rendererId = uuid();
const fixtures = wrapDefaultExport({
  first: { one: 'First' },
  second: 'Second',
});

testRenderer(
  'does not change locked fixture',
  {
    rendererId,
    locked: true,
    selectedFixtureId: { path: 'first', name: 'one' },
    fixtures,
  },
  async ({ renderer, selectFixture }) => {
    selectFixture({
      rendererId,
      fixtureId: { path: 'second' },
      fixtureState: {},
    });
    await setTimeout(50);
    expect(renderer.toJSON()).toBe('First');
  }
);

testRenderer(
  'does not close locked fixture',
  {
    rendererId,
    locked: true,
    selectedFixtureId: { path: 'first', name: 'one' },
    fixtures,
  },
  async ({ renderer, unselectFixture }) => {
    unselectFixture({ rendererId });
    await setTimeout(50);
    expect(renderer.toJSON()).toBe('First');
  }
);
