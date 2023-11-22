import retry from '@skidding/async-retry';
import { uuid } from 'react-cosmos-core';
import { testRenderer } from '../testHelpers/testRenderer.js';
import { wrapActSetTimeout } from '../testHelpers/wrapActSetTimeout.js';
import { wrapDefaultExport } from '../testHelpers/wrapDefaultExport.js';

const rendererId = uuid();
const fixtures = wrapDefaultExport({ first: null, second: null });

beforeAll(wrapActSetTimeout);

testRenderer(
  'renders lazy blank state message',
  { rendererId, fixtures, lazy: true },
  async ({ renderer }) => {
    await retry(() =>
      expect(renderer.toJSON()).toEqual('No fixture selected.')
    );
  }
);

testRenderer(
  'posts lazy fixture list',
  { rendererId, fixtures, lazy: true },
  async ({ fixtureListUpdate }) => {
    await fixtureListUpdate({
      rendererId,
      fixtures: {
        first: { type: 'single' },
        second: { type: 'single' },
      },
    });
  }
);

testRenderer(
  'posts lazy fixture list again on ping request',
  { rendererId, fixtures, lazy: true },
  async ({ fixtureListUpdate, pingRenderers, clearResponses }) => {
    await fixtureListUpdate({
      rendererId,
      fixtures: {
        first: { type: 'single' },
        second: { type: 'single' },
      },
    });
    clearResponses();
    pingRenderers();
    await fixtureListUpdate({
      rendererId,
      fixtures: {
        first: { type: 'single' },
        second: { type: 'single' },
      },
    });
  }
);

testRenderer(
  'posts lazy updated fixture list on "fixtures" prop change',
  { rendererId, fixtures, lazy: true },
  async ({ update, fixtureListUpdate }) => {
    await fixtureListUpdate({
      rendererId,
      fixtures: {
        first: { type: 'single' },
        second: { type: 'single' },
      },
    });
    update({
      rendererId,
      fixtures: { ...fixtures, ...wrapDefaultExport({ third: null }) },
    });
    await fixtureListUpdate({
      rendererId,
      fixtures: {
        first: { type: 'single' },
        second: { type: 'single' },
        third: { type: 'single' },
      },
    });
  }
);
